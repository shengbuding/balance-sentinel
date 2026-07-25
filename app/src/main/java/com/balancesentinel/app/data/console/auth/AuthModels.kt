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
    /** 最后活跃时间 */
    val lastActiveTime: Long = System.currentTimeMillis(),
    /** 是否永久有效 */
    val isPermanent: Boolean = true,
    /** 过期时间（仅非永久有效时使用） */
    val expireTime: Long? = null,
    /** 最后刷新时间 */
    val lastRefreshTime: Long = System.currentTimeMillis()
) {
    /**
     * 检查会话是否过期
     * 永久会话不过期，非永久会话检查过期时间
     */
    fun isExpired(): Boolean {
        // 永久有效的会话不过期
        if (isPermanent) return false
        // 非永久会话检查过期时间
        return expireTime != null && System.currentTimeMillis() > expireTime
    }

    /**
     * 检查会话是否有效
     * 注意：cookies 为空也认为有效（某些平台可能不返回 cookies）
     */
    fun isValid(): Boolean = !isExpired()

    /**
     * 刷新会话（更新刷新时间）
     */
    fun refresh(): ConsoleSession = copy(
        lastRefreshTime = System.currentTimeMillis(),
        lastActiveTime = System.currentTimeMillis()
    )

    /**
     * 更新最后活跃时间
     */
    fun updateActiveTime(): ConsoleSession = copy(
        lastActiveTime = System.currentTimeMillis()
    )
}
