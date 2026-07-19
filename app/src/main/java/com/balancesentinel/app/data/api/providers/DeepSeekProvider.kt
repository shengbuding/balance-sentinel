package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.*
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.UsageResponse
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek供应商实现
 * 唯一具有真实余额API的供应商
 */
class DeepSeekProvider : AiProvider {
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

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
        return try {
            val apiKey = config.apiKey
            val baseUrl = config.baseUrl ?: "https://api.deepseek.com"

            val request = Request.Builder()
                .url("$baseUrl/user/balance")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                    ?: throw IOException("Empty response body")

                if (!response.isSuccessful) {
                    when (response.code) {
                        401 -> return ProviderResult.Failure(
                            ProviderError.AuthError(providerType, "API Key 无效")
                        )
                        429 -> return ProviderResult.Failure(
                            ProviderError.RateLimitError(providerType)
                        )
                        else -> return ProviderResult.Failure(
                            ProviderError.ServerError(providerType, response.code, body)
                        )
                    }
                }

                val balanceResponse = json.decodeFromString<BalanceResponse>(body)
                val balances = balanceResponse.balanceInfos.map { info ->
                    BalanceEntry(
                        currency = info.currency,
                        totalBalance = info.totalBalance.toDoubleOrNull() ?: 0.0,
                        grantedBalance = info.grantedBalance.toDoubleOrNull(),
                        toppedUpBalance = info.toppedUpBalance.toDoubleOrNull()
                    )
                }

                ProviderResult.Success(
                    UnifiedBalance(
                        provider = providerType,
                        accountId = config.credentials["accountId"] ?: "",
                        isAvailable = balanceResponse.isAvailable,
                        balances = balances,
                        isEstimated = false
                    )
                )
            }
        } catch (e: IOException) {
            ProviderResult.Failure(ProviderError.NetworkError(providerType, e))
        } catch (e: Exception) {
            ProviderResult.Failure(ProviderError.InvalidResponseError(providerType, e.message ?: "未知错误", e))
        }
    }

    override suspend fun getUsage(
        config: ProviderConfig,
        startDate: String?,
        endDate: String?
    ): ProviderResult<UnifiedUsage> {
        return try {
            val apiKey = config.apiKey
            val baseUrl = config.baseUrl ?: "https://api.deepseek.com"

            val url = buildString {
                append("$baseUrl/v1/usage")
                if (startDate != null || endDate != null) {
                    append("?")
                    if (startDate != null) append("start_date=$startDate")
                    if (startDate != null && endDate != null) append("&")
                    if (endDate != null) append("end_date=$endDate")
                }
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                    ?: throw IOException("Empty response body")

                if (!response.isSuccessful) {
                    when (response.code) {
                        401 -> return ProviderResult.Failure(
                            ProviderError.AuthError(providerType, "API Key 无效")
                        )
                        429 -> return ProviderResult.Failure(
                            ProviderError.RateLimitError(providerType)
                        )
                        else -> return ProviderResult.Failure(
                            ProviderError.ServerError(providerType, response.code, body)
                        )
                    }
                }

                val usageResponse = json.decodeFromString<UsageResponse>(body)
                ProviderResult.Success(
                    UnifiedUsage(
                        provider = providerType,
                        accountId = config.credentials["accountId"] ?: "",
                        totalTokens = 0,
                        totalCost = 0.0
                    )
                )
            }
        } catch (e: IOException) {
            ProviderResult.Failure(ProviderError.NetworkError(providerType, e))
        } catch (e: Exception) {
            ProviderResult.Failure(ProviderError.InvalidResponseError(providerType, e.message ?: "未知错误", e))
        }
    }

    override fun validateApiKeyFormat(apiKey: String): Boolean {
        return apiKey.startsWith("sk-") && apiKey.length > 10
    }
}
