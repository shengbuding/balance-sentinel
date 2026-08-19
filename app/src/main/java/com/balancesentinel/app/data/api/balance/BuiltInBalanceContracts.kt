package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.PERCENTAGE_CURRENCY
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.QuotaPeriodSnapshot
import com.balancesentinel.app.data.api.QuotaSnapshot
import com.balancesentinel.app.data.api.UnifiedBalance
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

object BuiltInBalanceContracts {
    private val json = Json { ignoreUnknownKeys = true }

    const val OPEN_CODE_GO_USAGE_ENDPOINT = "https://opencode.ai/zen/go/v1/usage"

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

    /** Native OpenCode Go usage contract with rolling, weekly, and monthly quota windows. */
    val openCodeGo: BalanceContract = contract(
        type = BalanceProviderType.OPENCODE_GO,
        endpoint = OPEN_CODE_GO_USAGE_ENDPOINT
    ) { root, providerType, accountId ->
        val usage = root["usage"]?.jsonObject
            ?: throw SerializationException("missing usage")
        val periods = listOfNotNull(
            usage.quotaPeriod("rolling", "rolling_5h"),
            usage.quotaPeriod("weekly", "weekly"),
            usage.quotaPeriod("monthly", "monthly")
        )
        if (periods.isEmpty()) {
            throw SerializationException("missing quota periods")
        }
        val primary = periods.firstOrNull { it.id == "monthly" } ?: periods.last()
        UnifiedBalance(
            provider = providerType,
            accountId = accountId,
            isAvailable = root["isValid"]?.jsonPrimitive?.booleanOrNull
                ?: root["is_valid"]?.jsonPrimitive?.booleanOrNull
                ?: true,
            balances = listOf(
                BalanceEntry(
                    currency = PERCENTAGE_CURRENCY,
                    totalBalance = primary.remainingPercent,
                    unit = PERCENTAGE_CURRENCY,
                    quota = QuotaSnapshot(periods)
                )
            )
        )
    }

    fun resolve(providerType: ProviderType, baseUrl: String?): BalanceContract? =
        when (providerType) {
            ProviderType.DEEPSEEK -> deepSeek
            ProviderType.MODEL_ARK -> modelArk
            ProviderType.OPENCODE_GO -> openCodeGo
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

    private fun JsonObject.quotaPeriod(
        responseKey: String,
        id: String
    ): QuotaPeriodSnapshot? {
        val window = this[responseKey]?.jsonObject ?: return null
        val usedPercent = window["percent"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: window["usedPercent"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: window["used_percent"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: return null
        if (!usedPercent.isFinite() || usedPercent !in 0.0..100.0) {
            throw SerializationException("invalid $responseKey percentage")
        }
        return QuotaPeriodSnapshot(
            id = id,
            usedPercent = usedPercent,
            remainingPercent = 100.0 - usedPercent,
            resetsAt = window["resetsAt"]?.jsonPrimitive?.contentOrNull
                ?: window["resets_at"]?.jsonPrimitive?.contentOrNull,
            status = window["status"]?.jsonPrimitive?.contentOrNull
        )
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
        val totalBalance = amount(root)
        if (!totalBalance.isFinite()) {
            throw SerializationException("non-finite derived balance")
        }
        UnifiedBalance(
            provider = providerType,
            accountId = accountId,
            isAvailable = true,
            balances = listOf(
                BalanceEntry(
                    currency = currency,
                    totalBalance = totalBalance,
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
