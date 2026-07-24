package com.balancesentinel.app.data.console.auth

import com.balancesentinel.app.data.console.store.ConsoleSessionStore
import com.balancesentinel.app.data.util.Logger

/**
 * 控制台认证提供者基类
 * 抽取 DeepSeek 和 Mimo 的公共逻辑
 */
abstract class AbstractConsoleAuth(
    protected val sessionStore: ConsoleSessionStore,
    private val tag: String
) : ConsoleAuthProvider {

    override suspend fun isLoggedIn(): Boolean {
        return sessionStore.isLoggedIn(providerId)
    }

    override suspend fun getSession(): ConsoleSession? {
        val session = sessionStore.getSession(providerId)
        if (session != null && session.isExpired()) {
            Logger.w(tag, "Session expired for $providerId")
            return null
        }
        return session
    }

    override suspend fun logout() {
        sessionStore.clearSession(providerId)
        Logger.i(tag, "Logged out, session cleared")
    }

    /**
     * 获取登录邮箱（脱敏）
     */
    fun getMaskedEmail(): String? {
        val session = sessionStore.getSession(providerId) ?: return null
        return maskEmail(session.email)
    }

    protected fun maskEmail(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val atIndex = email.indexOf('@')
        if (atIndex <= 0) return "***"
        val prefix = email.first() + "***"
        val domain = email.substring(atIndex)
        return prefix + domain
    }
}
