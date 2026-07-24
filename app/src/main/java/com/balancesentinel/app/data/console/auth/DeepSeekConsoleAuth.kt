package com.balancesentinel.app.data.console.auth

import android.content.Context
import com.balancesentinel.app.data.console.store.ConsoleSessionStore
import com.balancesentinel.app.data.util.Logger

/**
 * DeepSeek 控制台登录提供者
 * 继承 AbstractConsoleAuth，仅实现 saveSession
 */
class DeepSeekConsoleAuth(
    context: Context
) : AbstractConsoleAuth(
    sessionStore = ConsoleSessionStore(context),
    tag = TAG
) {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "DeepSeek"

    override suspend fun saveSession(cookies: Map<String, String>, email: String?) {
        // DeepSeek 使用 userToken 作为认证 token
        val token = cookies["userToken"]
            ?: cookies["user_token"]
            ?: cookies["token"]
            ?: cookies["session_id"]

        Logger.i(TAG, "Saving session with ${cookies.size} cookies")

        val session = ConsoleSession(
            providerId = providerId,
            cookies = cookies,
            token = token,
            email = email,
            loginTime = System.currentTimeMillis(),
            expireTime = System.currentTimeMillis() + SESSION_DURATION_MS,
            lastRefreshTime = System.currentTimeMillis()
        )

        sessionStore.saveSession(session)
        Logger.i(TAG, "Session saved with ${cookies.size} cookies")
    }

    companion object {
        const val PROVIDER_ID = "deepseek"
        private const val TAG = "DeepSeekConsoleAuth"
        private const val SESSION_DURATION_MS = 48 * 3600 * 1000L // 48小时
    }
}
