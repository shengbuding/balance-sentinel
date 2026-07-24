package com.balancesentinel.app.data.console.auth

import kotlinx.serialization.Serializable

/**
 * 控制台登录会话
 */
@Serializable
data class ConsoleSession(
    /** 供应商标识 */
    val providerId: String,
    /** Cookie 键值对 */
    val cookies: Map<String, String>,
    /** Token */
    val token: String? = null,
    /** 用户邮箱 */
    val email: String? = null,
    /** 登录时间 */
    val loginTime: Long = System.currentTimeMillis(),
    /** 过期时间 */
    val expireTime: Long = System.currentTimeMillis() + 7 * 24 * 3600 * 1000L,
    /** 最后刷新时间 */
    val lastRefreshTime: Long = System.currentTimeMillis()
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expireTime
    fun isValid(): Boolean = !isExpired() && cookies.isNotEmpty()
    fun refresh(): ConsoleSession = copy(
        lastRefreshTime = System.currentTimeMillis(),
        expireTime = System.currentTimeMillis() + 7 * 24 * 3600 * 1000L
    )
}
