package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.*
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 模力方舟供应商实现
 * 支持查询钱包余额
 */
class ModelArkProvider : AiProvider {
    override val providerType = ProviderType.CUSTOM
    override val displayName = "模力方舟"
    override val supportedFeatures = setOf(ProviderFeature.BALANCE, ProviderFeature.MODELS)

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Serializable
    private data class WalletResponse(
        val balance: String = "0",
        val credit: String = "0",
        val frozen: String = "0",
        val paid_cash_amount: String = "0.00"
    )

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
        val apiKey = config.apiKey
        val baseUrl = config.baseUrl ?: "https://ai.gitee.com"

        return try {
            // 首先获取用户信息
            val profileRequest = Request.Builder()
                .url("$baseUrl/api/namespaces")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            var userId = ""
            client.newCall(profileRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val profileResponse = json.decodeFromString<NamespacesResponse>(body)
                    userId = profileResponse.data?.firstOrNull()?.path ?: ""
                }
            }

            if (userId.isBlank()) {
                return ProviderResult.Failure(
                    ProviderError.AuthError(providerType, "无法获取用户信息")
                )
            }

            // 获取钱包余额
            val walletRequest = Request.Builder()
                .url("$baseUrl/api/pay/$userId/wallet")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(walletRequest).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return ProviderResult.Failure(
                        ProviderError.NetworkError(
                            providerType,
                            java.io.IOException("API 返回错误 ${response.code}: $body")
                        )
                    )
                }

                val wallet = json.decodeFromString<WalletResponse>(body)

                val balance = wallet.balance.toDoubleOrNull() ?: 0.0
                val credit = wallet.credit.toDoubleOrNull() ?: 0.0

                ProviderResult.Success(
                    UnifiedBalance(
                        provider = providerType,
                        accountId = userId,
                        isAvailable = true,
                        balances = listOf(
                            BalanceEntry(
                                currency = "CNY",
                                totalBalance = balance,
                                unit = "元",
                                grantedBalance = credit,
                                toppedUpBalance = 0.0
                            )
                        ),
                        isEstimated = false
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e("ModelArk", "Failed to get balance: ${e.message}")
            ProviderResult.Failure(
                ProviderError.NetworkError(providerType, e)
            )
        }
    }

    override suspend fun getUsage(
        config: ProviderConfig,
        startDate: String?,
        endDate: String?
    ): ProviderResult<UnifiedUsage> {
        // 模力方舟的用量查询需要额外实现
        return ProviderResult.Failure(
            ProviderError.ApiUnavailableError(providerType, "用量查询暂不支持")
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
                hint = "请输入模力方舟 API Key"
            )
        )
    }

    @Serializable
    private data class NamespacesResponse(
        val success: Boolean = false,
        val data: List<NamespaceItem>? = null
    )

    @Serializable
    private data class NamespaceItem(
        val type: String = "",
        val path: String = "",
        val name: String = "",
        val image: String = ""
    )
}
