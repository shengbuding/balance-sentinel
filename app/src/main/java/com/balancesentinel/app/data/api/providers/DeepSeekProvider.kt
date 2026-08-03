package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.AiProvider
import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderFeature
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.UnifiedUsage
import com.balancesentinel.app.data.api.balance.BalanceQueryService
import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.debug.DebugCapturePolicy
import com.balancesentinel.app.data.model.UsageResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class DeepSeekProvider(
    private val balanceQueryService: BalanceQueryService = BalanceQueryService(),
    @Suppress("unused") private val debuggable: Boolean = DebugCapturePolicy.enabled()
) : AiProvider {
    override val providerType = ProviderType.DEEPSEEK
    override val displayName = "DeepSeek"
    override val supportedFeatures = setOf(
        ProviderFeature.BALANCE,
        ProviderFeature.USAGE,
        ProviderFeature.MODELS
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    internal fun getClientWithDebug(accountId: String?): OkHttpClient =
        if (accountId != null) {
            client.newBuilder().addInterceptor(DebugInterceptor(accountId)).build()
        } else {
            client
        }

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> =
        balanceQueryService.queryBalance(config)

    override suspend fun getUsage(
        config: ProviderConfig,
        startDate: String?,
        endDate: String?
    ): ProviderResult<UnifiedUsage> = try {
        val url = buildString {
            append("${config.baseUrl ?: "https://api.deepseek.com"}/v1/usage")
            if (startDate != null || endDate != null) {
                append("?")
                if (startDate != null) append("start_date=$startDate")
                if (startDate != null && endDate != null) append("&")
                if (endDate != null) append("end_date=$endDate")
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()

        getClientWithDebug(config.credentials["accountId"]).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use when (response.code) {
                    401 -> ProviderResult.Failure(ProviderError.AuthError(providerType, "API Key is invalid"))
                    429 -> ProviderResult.Failure(ProviderError.RateLimitError(providerType))
                    else -> ProviderResult.Failure(ProviderError.ServerError(providerType, response.code))
                }
            }

            val body = response.body?.string() ?: throw IOException("Empty response body")
            val usage = json.decodeFromString<UsageResponse>(body)
            val promptTokens = usage.data.sumOf { it.prompt_tokens }
            val completionTokens = usage.data.sumOf { it.completion_tokens }
            ProviderResult.Success(
                UnifiedUsage(
                    provider = providerType,
                    accountId = config.credentials["accountId"].orEmpty(),
                    totalTokens = usage.data.sumOf { it.total_tokens },
                    totalCost = (promptTokens / 1000.0) * 0.002 +
                        (completionTokens / 1000.0) * 0.01
                )
            )
        }
    } catch (error: IOException) {
        ProviderResult.Failure(ProviderError.NetworkError(providerType, error))
    } catch (error: Exception) {
        ProviderResult.Failure(
            ProviderError.InvalidResponseError(
                providerType,
                error.message ?: "Invalid usage response",
                error
            )
        )
    }

    override fun validateApiKeyFormat(apiKey: String): Boolean =
        apiKey.startsWith("sk-") && apiKey.length > 10
}
