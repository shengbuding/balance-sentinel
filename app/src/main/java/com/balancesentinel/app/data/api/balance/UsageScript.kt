package com.balancesentinel.app.data.api.balance

import kotlinx.serialization.Serializable

/**
 * 用量查询脚本配置
 */
@Serializable
data class UsageScript(
    val code: String,                    // JavaScript脚本代码
    val enabled: Boolean = true,         // 是否启用
    val timeout: Long = 15,              // 超时时间（秒）
    val apiKey: String? = null,          // 自定义API Key（为空则使用账户的）
    val baseUrl: String? = null,         // 自定义Base URL（为空则使用账户的）
    val accessToken: String? = null,     // 访问令牌（某些平台需要）
    val userId: String? = null           // 用户ID（某些平台需要）
)

/**
 * 脚本执行结果
 */
data class ScriptResult(
    val success: Boolean,
    val data: List<BalanceData>? = null,
    val error: String? = null
)

/**
 * 请求配置（从脚本中提取）
 */
data class RequestConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)
