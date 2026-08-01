package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.*
import com.balancesentinel.app.data.api.balance.BalanceQueryService
import com.balancesentinel.app.data.api.balance.PresetScripts
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI兼容供应商实现
 * 适用于使用OpenAI兼容API的供应商（Moonshot、豆包、百川、通义千问等）
 */
class OpenAiCompatibleProvider(
    override val providerType: ProviderType,
    private val defaultBaseUrl: String
) : AiProvider {
    override val displayName = providerType.displayName
    override val supportedFeatures = setOf(ProviderFeature.MODELS)

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 获取带有调试拦截器的OkHttpClient
     */
    private fun getClientWithDebug(accountId: String?): OkHttpClient {
        return if (accountId != null) {
            client.newBuilder()
                .addInterceptor(DebugInterceptor(accountId))
                .build()
        } else {
            client
        }
    }

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
        val apiKey = config.apiKey
        val baseUrl = config.baseUrl ?: defaultBaseUrl
        val accountId = config.credentials["accountId"]
        val accountLabel = config.credentials["accountLabel"]
        val customScript = config.settings["usageScript"]
            .takeIf { config.settings["usageScriptEnabled"] != "false" }

        Logger.i("OpenAiCompatibleProvider", "getBalance called: baseUrl=$baseUrl, hasCustomScript=${!customScript.isNullOrBlank()}")

        // 优先使用用户配置的自定义脚本
        if (!customScript.isNullOrBlank()) {
            Logger.i("OpenAiCompatibleProvider", "Using configured custom script")
            try {
                val script = UsageScript(code = customScript)
                val result = UsageScriptExecutor.execute(
                    script = script,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    accountId = accountId,
                    accountLabel = accountLabel,
                    providerType = providerType.displayName
                )
                if (result.success && result.data != null && result.data.isNotEmpty()) {
                    Logger.i("OpenAiCompatibleProvider", "Custom script succeeded")
                    val balanceData = result.data.first()
                    return ProviderResult.Success(
                        UnifiedBalance(
                            provider = providerType,
                            accountId = accountId ?: "",
                            isAvailable = balanceData.isValid ?: true,
                            balances = listOf(
                                BalanceEntry(
                                    currency = balanceData.unit ?: "CNY",
                                    totalBalance = balanceData.remaining ?: 0.0,
                                    unit = balanceData.unit ?: "元"
                                )
                            ),
                            isEstimated = false
                        )
                    )
                } else {
                    Logger.w("OpenAiCompatibleProvider", "Custom script returned no data: ${result.error}")
                }
            } catch (e: Exception) {
                Logger.e("OpenAiCompatibleProvider", "Custom script failed", e)
            }
        }

        // 其次尝试使用预置脚本
        val presetScript = PresetScripts.getPresetScript(baseUrl)
        if (presetScript != null) {
            Logger.i("OpenAiCompatibleProvider", "Found preset script for $baseUrl")
            try {
                val result = UsageScriptExecutor.execute(presetScript, apiKey, baseUrl, accountId)
                if (result.success && result.data != null && result.data.isNotEmpty()) {
                    Logger.i("OpenAiCompatibleProvider", "Preset script succeeded")
                    val balanceData = result.data.first()
                    return ProviderResult.Success(
                        UnifiedBalance(
                            provider = providerType,
                            accountId = accountId ?: "",
                            isAvailable = balanceData.isValid ?: true,
                            balances = listOf(
                                BalanceEntry(
                                    currency = balanceData.unit ?: "CNY",
                                    totalBalance = balanceData.remaining ?: 0.0,
                                    unit = balanceData.unit ?: "元"
                                )
                            ),
                            isEstimated = false
                        )
                    )
                } else {
                    Logger.w("OpenAiCompatibleProvider", "Preset script returned no data: ${result.error}")
                }
            } catch (e: Exception) {
                Logger.e("OpenAiCompatibleProvider", "Preset script failed", e)
            }
        } else {
            Logger.i("OpenAiCompatibleProvider", "No preset script found for $baseUrl")
        }

        // 如果预置脚本失败或没有预置脚本，使用通用查询
        Logger.i("OpenAiCompatibleProvider", "Falling back to generic query")
        val result = BalanceQueryService.queryBalance(baseUrl, apiKey, accountId)

        return if (result.success && result.data != null && result.data.isNotEmpty()) {
            val balanceData = result.data.first()
            ProviderResult.Success(
                UnifiedBalance(
                    provider = providerType,
                    accountId = accountId ?: "",
                    isAvailable = balanceData.isValid ?: true,
                    balances = listOf(
                        BalanceEntry(
                            currency = balanceData.unit ?: "CNY",
                            totalBalance = balanceData.remaining ?: 0.0,
                            unit = balanceData.unit ?: "元"
                        )
                    ),
                    isEstimated = false
                )
            )
        } else {
            // H3 修复：全部查询策略失败时返回 Failure 而非 Success(0.0)
            ProviderResult.Failure(
                ProviderError.ApiUnavailableError(
                    providerType,
                    result.error ?: "所有余额查询策略均失败"
                )
            )
        }
    }

    override suspend fun getUsage(
        config: ProviderConfig,
        startDate: String?,
        endDate: String?
    ): ProviderResult<UnifiedUsage> {
        // 大多数OpenAI兼容供应商不支持用量API
        return ProviderResult.Failure(
            ProviderError.ApiUnavailableError(providerType, "该供应商不支持用量查询")
        )
    }

    override fun validateApiKeyFormat(apiKey: String): Boolean {
        return apiKey.isNotBlank() && apiKey.length > 10
    }

    override fun getRequiredFields(): List<ConfigField> {
        return listOf(
            ConfigField(
                key = "apiKey",
                displayName = "API Key",
                type = FieldType.PASSWORD,
                required = true,
                hint = "请输入${displayName} API Key"
            )
        )
    }
}
