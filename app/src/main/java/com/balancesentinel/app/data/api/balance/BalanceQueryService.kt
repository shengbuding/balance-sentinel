package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.debug.DebugCapturePolicy
import com.balancesentinel.app.data.debug.DebugClientInstaller
import com.balancesentinel.app.data.network.BoundedResponseReader
import com.balancesentinel.app.data.network.DeepSeekTlsPolicyAdapter
import com.balancesentinel.app.data.network.NetworkResponseException
import com.balancesentinel.app.data.network.EncodedResponseLimitInterceptor
import com.balancesentinel.app.data.network.ResponseBudget
import com.balancesentinel.app.data.network.executeCancellable
import com.balancesentinel.app.data.network.originalCancellation
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class BalanceQueryService(
    private val callFactory: Call.Factory = defaultClient(),
    private val endpointOverride: ((BalanceContract) -> HttpUrl)? = null,
    private val debuggable: Boolean = DebugCapturePolicy.enabled()
) {
    suspend fun queryBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> = try {
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
                callFactoryFor(config, contract).executeCancellable(request) { response ->
                    val body = response.body?.let {
                        BoundedResponseReader(
                            ResponseBudget.BALANCE.maxDecodedBytes,
                            "balance-${config.providerType.name.lowercase()}"
                        ).readText(
                            it,
                            expectedContentType = if (response.isSuccessful) {
                                "application/json"
                            } else {
                                null
                            }
                        )
                    }.orEmpty()
                    val statusFailure = if (!response.isSuccessful) {
                        NetworkResponseException.httpStatus(
                            endpoint = "balance-${config.providerType.name.lowercase()}",
                            statusCode = response.code,
                            limitedBody = body
                        )
                    } else {
                        null
                    }
                    when (response.code) {
                        401 -> ProviderResult.Failure(
                            ProviderError.AuthError(
                                config.providerType,
                                "API Key is invalid",
                                cause = statusFailure
                            )
                        )
                        429 -> ProviderResult.Failure(
                            ProviderError.RateLimitError(
                                provider = config.providerType,
                                retryAfter = response.header("Retry-After")?.toLongOrNull(),
                                cause = statusFailure
                            )
                        )
                        in 200..299 -> {
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
                            ProviderError.ServerError(
                                config.providerType,
                                response.code,
                                responseBody = body,
                                cause = statusFailure
                            )
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled.originalCancellation()
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
    } catch (cancelled: CancellationException) {
        throw cancelled.originalCancellation()
    }

    internal fun callFactoryFor(
        config: ProviderConfig,
        contract: BalanceContract
    ): Call.Factory {
        val client = callFactory as? OkHttpClient
            ?: throw IllegalArgumentException(
                "Balance response budgets require an OkHttpClient Call.Factory"
            )
        val boundedClient = if (client.networkInterceptors.none {
                it is EncodedResponseLimitInterceptor
            }) {
            client.newBuilder()
                .addNetworkInterceptor(EncodedResponseLimitInterceptor(ResponseBudget.BALANCE))
                .build()
        } else {
            client
        }
        val tlsClient = if (config.providerType == com.balancesentinel.app.data.api.ProviderType.DEEPSEEK) {
            DeepSeekTlsPolicyAdapter.configure(boundedClient.newBuilder()).build()
        } else {
            boundedClient
        }
        val accountId = config.credentials["accountId"] ?: return tlsClient
        return DebugClientInstaller.install(
            client = tlsClient,
            debuggable = debuggable,
            interceptor = DebugInterceptor(
                    accountId = accountId,
                    accountLabel = config.credentials["accountLabel"],
                    providerType = config.providerType.displayName,
                    baseUrl = contract.endpoint.toString(),
                    isCustomScript = false,
                    scriptPreview = null
                )
        )
    }

    companion object {
        const val CUSTOM_SCRIPT_REQUIRED_MESSAGE = "该供应商需要自定义余额脚本"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
