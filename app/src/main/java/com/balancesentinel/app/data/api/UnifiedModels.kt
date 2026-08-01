package com.balancesentinel.app.data.api

import kotlinx.serialization.Serializable

/**
 * 统一余额响应
 */
@Serializable
data class UnifiedBalance(
    val provider: ProviderType,
    val accountId: String,
    val isAvailable: Boolean,
    val balances: List<BalanceEntry>,
    val fetchedAt: Long = System.currentTimeMillis(),
    val isEstimated: Boolean = false  // 是否为估算值
)

/**
 * 余额条目
 */
@Serializable
data class BalanceEntry(
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double? = null,
    val toppedUpBalance: Double? = null,
    val unit: String = "元"
)

/**
 * 统一用量响应
 */
@Serializable
data class UnifiedUsage(
    val provider: ProviderType,
    val accountId: String,
    val totalTokens: Long,
    val totalCost: Double,
    val currency: String = "CNY",
    val period: UsagePeriod? = null
)

/**
 * 用量周期
 */
@Serializable
data class UsagePeriod(
    val startDate: String,
    val endDate: String
)

/**
 * 供应商配置
 */
@Serializable
data class ProviderConfig(
    val providerType: ProviderType,
    val credentials: Map<String, String>,  // apiKey, secretKey, orgId等
    val settings: Map<String, String> = emptyMap()  // baseUrl覆盖、timeout等
) {
    val apiKey: String
        get() = credentials["apiKey"] ?: ""

    val secretKey: String?
        get() = credentials["secretKey"]

    val orgId: String?
        get() = credentials["orgId"]

    val baseUrl: String?
        get() = settings["baseUrl"]
}

/**
 * 配置字段定义（用于动态UI生成）
 */
data class ConfigField(
    val key: String,
    val displayName: String,
    val type: FieldType,
    val required: Boolean,
    val defaultValue: String? = null,
    val hint: String? = null
)

/**
 * 字段类型
 */
enum class FieldType {
    TEXT,
    PASSWORD,
    URL,
    SELECT
}

/**
 * 供应商特性
 */
enum class ProviderFeature {
    BALANCE,    // 支持余额查询
    USAGE,      // 支持用量查询
    MODELS      // 支持模型列表
}
