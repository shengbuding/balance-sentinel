package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.*
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

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
        // 大多数OpenAI兼容供应商没有余额API
        // 返回估算余额，由LocalUsageTracker提供数据
        return ProviderResult.Success(
            UnifiedBalance(
                provider = providerType,
                accountId = config.credentials["accountId"] ?: "",
                isAvailable = true,
                balances = listOf(
                    BalanceEntry(
                        currency = "CNY",
                        totalBalance = 0.0,
                        unit = "元"
                    )
                ),
                isEstimated = true
            )
        )
    }

    override suspend fun getUsage(
        config: ProviderConfig,
        startDate: String?,
        endDate: String?
    ): ProviderResult<UnifiedUsage> {
        // 大多数供应商不支持用量API
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
