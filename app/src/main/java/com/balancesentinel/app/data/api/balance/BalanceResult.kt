package com.balancesentinel.app.data.api.balance

/**
 * 余额查询结果
 */
data class BalanceResult(
    val success: Boolean,
    val data: List<BalanceData>? = null,
    val error: String? = null
)

/**
 * 余额数据
 */
data class BalanceData(
    val planName: String? = null,      // 计划名称（如币种）
    val remaining: Double? = null,     // 剩余额度
    val total: Double? = null,         // 总额度
    val used: Double? = null,          // 已使用
    val unit: String? = null,          // 货币单位（CNY, USD等）
    val isValid: Boolean? = null,      // 是否有效（余额是否充足）
    val invalidMessage: String? = null // 无效时的提示信息
)
