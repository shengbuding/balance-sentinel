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
import com.balancesentinel.app.data.api.balance.ScriptExecutionException
import com.balancesentinel.app.data.api.balance.ScriptExecutionResult
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshFailure
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
            "getBalance called: hasCustomScript=${!customScript.isNullOrBlank()}"
        )

        if (!customScript.isNullOrBlank()) {
            return when (val result = executeCustomScript(effectiveConfig, customScript)) {
                is ScriptExecutionResult.Success -> {
                    val balance = result.balances.firstOrNull()
                        ?: return scriptFailure(
                            RefreshFailure.ResponseSchemaFailure("Script returned no balance data")
                        )
                    Logger.i("OpenAiCompatibleProvider", "Custom script succeeded")
                    ProviderResult.Success(balance.toUnifiedBalance(providerType, accountId))
                }
                is ScriptExecutionResult.Failure -> scriptFailure(result.failure)
            }
        }

        return balanceQueryService.queryBalance(effectiveConfig)
    }

    private suspend fun executeCustomScript(
        config: ProviderConfig,
        source: String
    ): ScriptExecutionResult {
        Logger.i("OpenAiCompatibleProvider", "Using custom script")
        val enabledSetting = config.settings["usageScriptEnabled"]
        val enabled = enabledSetting?.toBooleanStrictOrNull() ?: (enabledSetting == null)
        val authorizedOrigins = config.settings["authorizedScriptOrigins"]
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val account = AccountInfo(
            id = config.credentials["accountId"].orEmpty(),
            label = config.credentials["accountLabel"].orEmpty(),
            apiKey = config.apiKey,
            providerType = providerType,
            extraSettings = config.settings,
            usageScript = source,
            usageScriptEnabled = enabled,
            authorizedScriptOrigins = authorizedOrigins
        )
        return UsageScriptExecutor.execute(
            script = UsageScript(
                code = source,
                enabled = enabled,
                baseUrl = config.baseUrl,
                accessToken = config.credentials["accessToken"],
                userId = config.credentials["userId"]
            ),
            account = account
        )
    }

    private fun scriptFailure(failure: RefreshFailure): ProviderResult.Failure =
        ProviderResult.Failure(
            ProviderError.InvalidResponseError(
                provider = providerType,
                message = "Custom script execution failed",
                cause = ScriptExecutionException(failure)
            )
        )

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
                    totalBalance = checkNotNull(remaining),
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
