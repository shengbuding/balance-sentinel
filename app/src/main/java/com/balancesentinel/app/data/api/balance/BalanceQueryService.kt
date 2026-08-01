package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.debug.DebugInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class BalanceQueryService(
    private val callFactory: Call.Factory = defaultClient(),
    private val endpointOverride: ((BalanceContract) -> HttpUrl)? = null
) {
    suspend fun queryBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) {
                return@withContext ProviderResult.Failure(
                    ProviderError.AuthError(config.providerType, "API Key is empty")
                )
            }

            val contract = BuiltInBalanceContracts.resolve(
                providerType = config.providerType,
                baseUrl = config.baseUrl
            ) ?: return@withContext ProviderResult.Failure(
                ProviderError.ApiUnavailableError(
                    config.providerType,
                    CUSTOM_SCRIPT_REQUIRED_MESSAGE
                )
            )

            val endpoint = endpointOverride?.invoke(contract) ?: contract.endpoint
            val request = contract.request(config.apiKey, endpoint)

            try {
                callFactoryFor(config, contract).newCall(request).execute().use { response ->
                    when (response.code) {
                        401 -> ProviderResult.Failure(
                            ProviderError.AuthError(config.providerType, "API Key is invalid")
                        )
                        429 -> ProviderResult.Failure(
                            ProviderError.RateLimitError(
                                provider = config.providerType,
                                retryAfter = response.header("Retry-After")?.toLongOrNull()
                            )
                        )
                        in 200..299 -> {
                            val body = response.body?.string().orEmpty()
                            if (body.isBlank()) {
                                ProviderResult.Failure(
                                    ProviderError.InvalidResponseError(
                                        config.providerType,
                                        "Empty response body"
                                    )
                                )
                            } else {
                                contract.parse(
                                    body = body,
                                    providerType = config.providerType,
                                    accountId = config.credentials["accountId"].orEmpty()
                                )
                            }
                        }
                        else -> ProviderResult.Failure(
                            ProviderError.ServerError(config.providerType, response.code)
                        )
                    }
                }
            } catch (error: IOException) {
                ProviderResult.Failure(ProviderError.NetworkError(config.providerType, error))
            } catch (error: Exception) {
                ProviderResult.Failure(
                    ProviderError.InvalidResponseError(
                        config.providerType,
                        "Invalid balance response"
                    )
                )
            }
        }

    private fun callFactoryFor(
        config: ProviderConfig,
        contract: BalanceContract
    ): Call.Factory {
        val accountId = config.credentials["accountId"] ?: return callFactory
        val client = callFactory as? OkHttpClient ?: return callFactory
        return client.newBuilder()
            .addInterceptor(
                DebugInterceptor(
                    accountId = accountId,
                    accountLabel = config.credentials["accountLabel"],
                    providerType = config.providerType.displayName,
                    baseUrl = contract.endpoint.toString(),
                    isCustomScript = false,
                    scriptPreview = null
                )
            )
            .build()
    }

    companion object {
        const val CUSTOM_SCRIPT_REQUIRED_MESSAGE = "该供应商需要自定义余额脚本"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
