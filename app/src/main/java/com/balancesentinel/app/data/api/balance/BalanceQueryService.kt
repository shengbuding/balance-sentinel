package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 余额查询服务
 * 支持多种供应商的余额查询
 */
object BalanceQueryService {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 查询余额
     * @param baseUrl API基础URL
     * @param apiKey API密钥
     * @param accountId 账户ID（用于调试日志）
     * @param accountLabel 账户标签（用于调试日志）
     * @return 余额查询结果
     */
    suspend fun queryBalance(
        baseUrl: String,
        apiKey: String,
        accountId: String? = null,
        accountLabel: String? = null
    ): BalanceResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext BalanceResult(
                success = false,
                error = "API Key为空"
            )
        }

        // 检测供应商类型
        val providerType = BalanceProviderType.detectFromUrl(baseUrl)
        val providerTypeName = providerType?.displayName ?: "CUSTOM"

        if (providerType == null) {
            // 未知供应商，尝试通用查询
            return@withContext queryCustomProvider(baseUrl, apiKey, accountId, accountLabel, providerTypeName)
        }

        // 根据供应商类型调用对应的查询方法
        when (providerType) {
            BalanceProviderType.DEEPSEEK -> queryDeepSeek(apiKey, accountId, accountLabel)
            BalanceProviderType.STEPFUN -> queryStepFun(apiKey, accountId, accountLabel)
            BalanceProviderType.SILICONFLOW -> querySiliconFlow(apiKey, true, accountId, accountLabel)
            BalanceProviderType.SILICONFLOW_EN -> querySiliconFlow(apiKey, false, accountId, accountLabel)
            BalanceProviderType.OPENROUTER -> queryOpenRouter(apiKey, accountId, accountLabel)
            BalanceProviderType.NOVITA -> queryNovita(apiKey, accountId, accountLabel)
            BalanceProviderType.MODEL_ARK -> queryModelArk(apiKey, accountId, accountLabel)
            BalanceProviderType.CUSTOM -> queryCustomProvider(baseUrl, apiKey, accountId, accountLabel, providerTypeName)
        }
    }

    /**
     * 创建带有调试拦截器的OkHttpClient
     */
    private fun createClient(
        accountId: String?,
        accountLabel: String? = null,
        providerType: String? = null,
        baseUrl: String? = null,
        isCustomScript: Boolean = false,
        scriptPreview: String? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)

        if (accountId != null) {
            builder.addInterceptor(DebugInterceptor(
                accountId = accountId,
                accountLabel = accountLabel,
                providerType = providerType,
                baseUrl = baseUrl,
                isCustomScript = isCustomScript,
                scriptPreview = scriptPreview
            ))
        }

        return builder.build()
    }

    /**
     * 执行HTTP请求并解析响应
     */
    private fun executeRequest(
        client: OkHttpClient,
        request: Request
    ): JsonObject {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw IOException("API error (HTTP ${response.code}): $body")
        }

        return json.parseToJsonElement(body).jsonObject
    }

    // ═══════════════════════════════════════════════════════════
    // DeepSeek
    // GET https://api.deepseek.com/user/balance
    // Response: { balance_infos: [{ currency, total_balance, granted_balance, topped_up_balance }], is_available }
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryDeepSeek(apiKey: String, accountId: String?, accountLabel: String? = null): BalanceResult {
        return try {
            val client = createClient(accountId)
            val request = Request.Builder()
                .url("https://api.deepseek.com/user/balance")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = executeRequest(client, request)
            val isAvailable = body["is_available"]?.jsonPrimitive?.booleanOrNull ?: true
            val balanceInfos = body["balance_infos"]?.jsonArray ?: emptyList()

            val data = balanceInfos.map { info ->
                val infoObj = info.jsonObject
                val currency = infoObj["currency"]?.jsonPrimitive?.content ?: "CNY"
                val totalBalance = parseDoubleField(infoObj, "total_balance")

                BalanceData(
                    planName = currency,
                    remaining = totalBalance,
                    unit = currency,
                    isValid = isAvailable,
                    invalidMessage = if (!isAvailable) "余额不足" else null
                )
            }

            BalanceResult(success = true, data = data)
        } catch (e: Exception) {
            Logger.e("BalanceQuery", "DeepSeek query failed", e)
            BalanceResult(success = false, error = e.message ?: "查询失败")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // StepFun
    // GET https://api.stepfun.com/v1/accounts
    // Response: { object, type, balance, total_cash_balance, total_voucher_balance }
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryStepFun(apiKey: String, accountId: String?, accountLabel: String? = null): BalanceResult {
        return try {
            val client = createClient(accountId)
            val request = Request.Builder()
                .url("https://api.stepfun.com/v1/accounts")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = executeRequest(client, request)
            val balance = parseDoubleField(body, "balance") ?: 0.0

            BalanceResult(
                success = true,
                data = listOf(
                    BalanceData(
                        planName = "StepFun",
                        remaining = balance,
                        unit = "CNY",
                        isValid = true
                    )
                )
            )
        } catch (e: Exception) {
            Logger.e("BalanceQuery", "StepFun query failed", e)
            BalanceResult(success = false, error = e.message ?: "查询失败")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SiliconFlow
    // GET https://api.siliconflow.cn/v1/user/info (or .com for EN)
    // Response: { code, data: { balance, chargeBalance, totalBalance, status } }
    // ═══════════════════════════════════════════════════════════

    private suspend fun querySiliconFlow(apiKey: String, isCn: Boolean, accountId: String?, accountLabel: String? = null): BalanceResult {
        return try {
            val domain = if (isCn) "api.siliconflow.cn" else "api.siliconflow.com"
            val client = createClient(accountId)
            val request = Request.Builder()
                .url("https://$domain/v1/user/info")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = executeRequest(client, request)
            val data = body["data"]?.jsonObject
                ?: return BalanceResult(success = false, error = "响应格式错误")

            val totalBalance = parseDoubleField(data, "totalBalance") ?: 0.0
            val unit = if (isCn) "CNY" else "USD"
            val planName = if (isCn) "SiliconFlow" else "SiliconFlow (EN)"

            BalanceResult(
                success = true,
                data = listOf(
                    BalanceData(
                        planName = planName,
                        remaining = totalBalance,
                        unit = unit,
                        isValid = true
                    )
                )
            )
        } catch (e: Exception) {
            Logger.e("BalanceQuery", "SiliconFlow query failed", e)
            BalanceResult(success = false, error = e.message ?: "查询失败")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // OpenRouter
    // GET https://openrouter.ai/api/v1/credits
    // Response: { data: { total_credits, total_usage } }
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryOpenRouter(apiKey: String, accountId: String?, accountLabel: String? = null): BalanceResult {
        return try {
            val client = createClient(accountId)
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/credits")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = executeRequest(client, request)
            val data = body["data"]?.jsonObject ?: body
            val totalCredits = parseDoubleField(data, "total_credits") ?: 0.0
            val totalUsage = parseDoubleField(data, "total_usage") ?: 0.0
            val remaining = totalCredits - totalUsage

            BalanceResult(
                success = true,
                data = listOf(
                    BalanceData(
                        planName = "OpenRouter",
                        remaining = remaining,
                        total = totalCredits,
                        used = totalUsage,
                        unit = "USD",
                        isValid = remaining > 0,
                        invalidMessage = if (remaining <= 0) "额度已用完" else null
                    )
                )
            )
        } catch (e: Exception) {
            Logger.e("BalanceQuery", "OpenRouter query failed", e)
            BalanceResult(success = false, error = e.message ?: "查询失败")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Novita AI
    // GET https://api.novita.ai/v3/user/balance
    // Response: { availableBalance, cashBalance, creditLimit, outstandingInvoices }
    // 金额单位：0.0001 USD
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryNovita(apiKey: String, accountId: String?, accountLabel: String? = null): BalanceResult {
        return try {
            val client = createClient(accountId)
            val request = Request.Builder()
                .url("https://api.novita.ai/v3/user/balance")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = executeRequest(client, request)
            // Novita金额单位为0.0001 USD，需除以10000转为USD
            val available = (parseDoubleField(body, "availableBalance") ?: 0.0) / 10000.0

            BalanceResult(
                success = true,
                data = listOf(
                    BalanceData(
                        planName = "Novita AI",
                        remaining = available,
                        unit = "USD",
                        isValid = available > 0,
                        invalidMessage = if (available <= 0) "余额不足" else null
                    )
                )
            )
        } catch (e: Exception) {
            Logger.e("BalanceQuery", "Novita query failed", e)
            BalanceResult(success = false, error = e.message ?: "查询失败")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 模力方舟
    // 尝试多个可能的端点
    // 文档：https://ai.gitee.com/docs/openapi/v1
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryModelArk(apiKey: String, accountId: String?, accountLabel: String? = null): BalanceResult {
        return try {
            val client = createClient(accountId)
            val request = Request.Builder()
                .url("https://ai.gitee.com/v1/tokens/packages/balance")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = executeRequest(client, request)

            // 模力方舟响应格式：
            // { "total_amount": 100, "used_amount": 30, "balance": 70, "details": [...] }
            val balance = parseDoubleField(body, "balance") ?: 0.0
            val totalAmount = parseDoubleField(body, "total_amount") ?: 0.0
            val usedAmount = parseDoubleField(body, "used_amount") ?: 0.0

            BalanceResult(
                success = true,
                data = listOf(
                    BalanceData(
                        planName = "模力方舟",
                        remaining = balance,
                        unit = "Token",
                        isValid = balance > 0,
                        invalidMessage = if (balance <= 0) "余额不足" else null
                    )
                )
            )
        } catch (e: Exception) {
            Logger.e("BalanceQuery", "ModelArk query failed", e)
            BalanceResult(success = false, error = e.message ?: "查询失败")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 自定义供应商（通用查询）
    // 尝试多个常见的余额API端点
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryCustomProvider(
        baseUrl: String,
        apiKey: String,
        accountId: String? = null,
        accountLabel: String? = null,
        providerType: String? = "CUSTOM"
    ): BalanceResult {
        val normalizedBaseUrl = baseUrl.trimEnd('/')

        // 智能处理URL：如果baseUrl已经包含版本前缀（如/v1），则使用不带版本的端点
        val hasVersionPrefix = normalizedBaseUrl.matches(Regex(".*/v\\d+$"))

        // 尝试多个常见的余额API端点
        val endpoints = if (hasVersionPrefix) {
            // baseUrl已包含版本前缀，使用相对路径
            listOf(
                "/user/info",
                "/dashboard/billing/usage",
                "/billing/usage",
                "/user/balance",
                "/accounts"
            )
        } else {
            // baseUrl不包含版本前缀，使用完整路径
            listOf(
                "/v1/user/info",
                "/v1/dashboard/billing/usage",
                "/v1/billing/usage",
                "/v1/user/balance",
                "/api/user/balance"
            )
        }

        for (endpoint in endpoints) {
            try {
                val client = createClient(
                    accountId = accountId,
                    accountLabel = accountLabel,
                    providerType = providerType,
                    baseUrl = normalizedBaseUrl,
                    isCustomScript = false,
                    scriptPreview = null
                )
                val request = Request.Builder()
                    .url("$normalizedBaseUrl$endpoint")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val jsonBody = json.parseToJsonElement(body).jsonObject

                    // 尝试解析余额信息
                    val balance = extractBalanceFromResponse(jsonBody)
                    if (balance != null) {
                        return BalanceResult(
                            success = true,
                            data = listOf(balance)
                        )
                    }
                }
                response.close()
            } catch (e: Exception) {
                // 继续尝试下一个端点
                continue
            }
        }

        return BalanceResult(
            success = false,
            error = "无法查询余额：未找到支持的API端点"
        )
    }

    /**
     * 从响应中提取余额信息（通用解析）
     */
    private fun extractBalanceFromResponse(body: JsonObject): BalanceData? {
        // 尝试多种常见的字段名
        val balanceFields = listOf(
            "balance", "totalBalance", "total_balance",
            "availableBalance", "available_balance",
            "remaining", "credits"
        )

        for (field in balanceFields) {
            val value = parseDoubleField(body, field)
            if (value != null) {
                return BalanceData(
                    remaining = value,
                    unit = "CNY",
                    isValid = true
                )
            }
        }

        // 尝试解析嵌套的data对象
        val data = body["data"]?.jsonObject
        if (data != null) {
            for (field in balanceFields) {
                val value = parseDoubleField(data, field)
                if (value != null) {
                    return BalanceData(
                        remaining = value,
                        unit = "CNY",
                        isValid = true
                    )
                }
            }
        }

        return null
    }

    /**
     * 解析JSON字段为Double，兼容数字和字符串格式
     */
    private fun parseDoubleField(obj: JsonObject, field: String): Double? {
        val element = obj[field] ?: return null
        return try {
            element.jsonPrimitive.doubleOrNull
                ?: element.jsonPrimitive.content.toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
