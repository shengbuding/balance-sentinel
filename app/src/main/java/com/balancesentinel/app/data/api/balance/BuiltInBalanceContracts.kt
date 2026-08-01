package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

object BuiltInBalanceContracts {
    private val json = Json { ignoreUnknownKeys = true }

    val deepSeek: BalanceContract = contract(
        type = BalanceProviderType.DEEPSEEK,
        endpoint = "https://api.deepseek.com/user/balance"
    ) { root, providerType, accountId ->
        val entries = root["balance_infos"]?.jsonArray
            ?: throw SerializationException("missing balance_infos")
        if (entries.isEmpty()) {
            throw SerializationException("empty balance_infos")
        }
        val balances = entries.map { element ->
            val entry = element.jsonObject
            val currency = entry["currency"]?.jsonPrimitive?.content
                ?.takeIf(String::isNotBlank)
                ?: throw SerializationException("missing currency")
            BalanceEntry(
                currency = currency,
                totalBalance = entry.requiredFiniteDouble("total_balance"),
                grantedBalance = entry.optionalFiniteDouble("granted_balance"),
                toppedUpBalance = entry.optionalFiniteDouble("topped_up_balance"),
                unit = currency
            )
        }
        UnifiedBalance(
            provider = providerType,
            accountId = accountId,
            isAvailable = root["is_available"]?.jsonPrimitive?.booleanOrNull ?: true,
            balances = balances
        )
    }

    val stepFun: BalanceContract = singleAmountContract(
        type = BalanceProviderType.STEPFUN,
        endpoint = "https://api.stepfun.com/v1/accounts",
        amount = { root -> root.requiredFiniteDouble("balance") },
        currency = "CNY"
    )

    val siliconFlowCn: BalanceContract = siliconFlowContract(
        type = BalanceProviderType.SILICONFLOW,
        endpoint = "https://api.siliconflow.cn/v1/user/info",
        currency = "CNY"
    )

    val siliconFlowCom: BalanceContract = siliconFlowContract(
        type = BalanceProviderType.SILICONFLOW_EN,
        endpoint = "https://api.siliconflow.com/v1/user/info",
        currency = "USD"
    )

    val openRouter: BalanceContract = singleAmountContract(
        type = BalanceProviderType.OPENROUTER,
        endpoint = "https://openrouter.ai/api/v1/credits",
        amount = { root ->
            val data = root["data"]?.jsonObject
                ?: throw SerializationException("missing data")
            data.requiredFiniteDouble("total_credits") -
                data.requiredFiniteDouble("total_usage")
        },
        currency = "USD"
    )

    val novita: BalanceContract = singleAmountContract(
        type = BalanceProviderType.NOVITA,
        endpoint = "https://api.novita.ai/v3/user/balance",
        amount = { root -> root.requiredFiniteDouble("availableBalance") / 10_000.0 },
        currency = "USD"
    )

    val modelArk: BalanceContract = singleAmountContract(
        type = BalanceProviderType.MODEL_ARK,
        endpoint = "https://ai.gitee.com/v1/tokens/packages/balance",
        amount = { root -> root.requiredFiniteDouble("balance") },
        currency = "Token"
    )

    fun resolve(providerType: ProviderType, baseUrl: String?): BalanceContract? =
        when (providerType) {
            ProviderType.DEEPSEEK -> deepSeek
            ProviderType.MODEL_ARK -> modelArk
            ProviderType.CUSTOM -> when (BalanceProviderType.detectFromUrl(baseUrl.orEmpty())) {
                BalanceProviderType.STEPFUN -> stepFun
                BalanceProviderType.SILICONFLOW -> siliconFlowCn
                BalanceProviderType.SILICONFLOW_EN -> siliconFlowCom
                BalanceProviderType.OPENROUTER -> openRouter
                BalanceProviderType.NOVITA -> novita
                else -> null
            }
            else -> null
        }

    private fun siliconFlowContract(
        type: BalanceProviderType,
        endpoint: String,
        currency: String
    ): BalanceContract = singleAmountContract(
        type = type,
        endpoint = endpoint,
        amount = { root ->
            val data = root["data"]?.jsonObject
                ?: throw SerializationException("missing data")
            data.requiredFiniteDouble("balance")
        },
        currency = currency
    )

    private fun singleAmountContract(
        type: BalanceProviderType,
        endpoint: String,
        amount: (JsonObject) -> Double,
        currency: String
    ): BalanceContract = contract(type, endpoint) { root, providerType, accountId ->
        UnifiedBalance(
            provider = providerType,
            accountId = accountId,
            isAvailable = true,
            balances = listOf(
                BalanceEntry(
                    currency = currency,
                    totalBalance = amount(root),
                    unit = currency
                )
            )
        )
    }

    private fun contract(
        type: BalanceProviderType,
        endpoint: String,
        parser: (JsonObject, ProviderType, String) -> UnifiedBalance
    ): BalanceContract = object : BalanceContract {
        override val type = type
        override val endpoint = endpoint.toHttpUrl()

        override fun parse(
            body: String,
            providerType: ProviderType,
            accountId: String
        ): ProviderResult<UnifiedBalance> = try {
            val root = json.parseToJsonElement(body).jsonObject
            ProviderResult.Success(parser(root, providerType, accountId))
        } catch (_: Exception) {
            ProviderResult.Failure(
                ProviderError.InvalidResponseError(
                    providerType,
                    "Invalid balance response"
                )
            )
        }
    }
}
