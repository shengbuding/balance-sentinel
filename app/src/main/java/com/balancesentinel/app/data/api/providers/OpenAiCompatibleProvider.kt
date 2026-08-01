package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.AiProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ConfigField
import com.balancesentinel.app.data.api.FieldType
import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderFeature
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.UnifiedUsage
import com.balancesentinel.app.data.api.balance.BalanceData
import com.balancesentinel.app.data.api.balance.BalanceQueryService
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import com.balancesentinel.app.data.util.Logger

class OpenAiCompatibleProvider(
    override val providerType: ProviderType,
    private val defaultBaseUrl: String,
    private val balanceQueryService: BalanceQueryService = BalanceQueryService()
) : AiProvider {
    override val displayName = providerType.displayName
    override val supportedFeatures = setOf(ProviderFeature.MODELS)

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
        val effectiveConfig = if (config.baseUrl.isNullOrBlank()) {
            config.copy(settings = config.settings + ("baseUrl" to defaultBaseUrl))
        } else {
            config
        }
        val accountId = effectiveConfig.credentials["accountId"].orEmpty()
        val customScript = effectiveConfig.settings["usageScript"]

        Logger.i(
            "OpenAiCompatibleProvider",
            "getBalance called: baseUrl=${effectiveConfig.baseUrl}, " +
                "hasCustomScript=${!customScript.isNullOrBlank()}"
        )

        if (!customScript.isNullOrBlank()) {
            val scripted = executeCustomScript(effectiveConfig, customScript)
            return if (scripted != null) {
                ProviderResult.Success(
                    scripted.toUnifiedBalance(providerType, accountId)
                )
            } else {
                ProviderResult.Failure(
                    ProviderError.ApiUnavailableError(
                        providerType,
                        "自定义余额脚本执行失败"
                    )
                )
            }
        }

        return balanceQueryService.queryBalance(effectiveConfig)
    }

    private suspend fun executeCustomScript(
        config: ProviderConfig,
        source: String
    ): BalanceData? = try {
        Logger.i("OpenAiCompatibleProvider", "Using custom script")
        val result = UsageScriptExecutor.execute(
            script = UsageScript(code = source),
            apiKey = config.apiKey,
            baseUrl = checkNotNull(config.baseUrl),
            accountId = config.credentials["accountId"],
            accountLabel = config.credentials["accountLabel"],
            providerType = providerType.displayName
        )
        result.data?.firstOrNull().also { balance ->
            if (result.success && balance != null) {
                Logger.i("OpenAiCompatibleProvider", "Custom script succeeded")
            } else {
                Logger.w("OpenAiCompatibleProvider", "Custom script returned no data")
            }
        }?.takeIf { result.success }
    } catch (_: Exception) {
        Logger.e("OpenAiCompatibleProvider", "Custom script failed")
        null
    }

    private fun BalanceData.toUnifiedBalance(
        providerType: ProviderType,
        accountId: String
    ): UnifiedBalance {
        val currency = unit ?: "CNY"
        return UnifiedBalance(
            provider = providerType,
            accountId = accountId,
            isAvailable = isValid ?: true,
            balances = listOf(
                BalanceEntry(
                    currency = currency,
                    totalBalance = remaining ?: 0.0,
                    unit = currency
                )
            ),
            isEstimated = false
        )
    }

    override suspend fun getUsage(
        config: ProviderConfig,
        startDate: String?,
        endDate: String?
    ): ProviderResult<UnifiedUsage> = ProviderResult.Failure(
        ProviderError.ApiUnavailableError(
            providerType,
            "This provider does not support usage queries"
        )
    )

    override fun validateApiKeyFormat(apiKey: String): Boolean =
        apiKey.isNotBlank() && apiKey.length > 10

    override fun getRequiredFields(): List<ConfigField> = listOf(
        ConfigField(
            key = "apiKey",
            displayName = "API Key",
            type = FieldType.PASSWORD,
            required = true,
            hint = "Enter $displayName API Key"
        )
    )
}
