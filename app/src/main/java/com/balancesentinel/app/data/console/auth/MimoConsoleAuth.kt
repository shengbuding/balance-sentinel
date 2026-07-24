package com.balancesentinel.app.data.console.auth

import android.content.Context
import com.balancesentinel.app.data.console.store.ConsoleSessionStore
import com.balancesentinel.app.data.util.Logger

/**
 * Mimo 控制台登录提供者
 * 继承 AbstractConsoleAuth，仅实现 saveSession
 */
class MimoConsoleAuth(
    context: Context
) : AbstractConsoleAuth(
    sessionStore = ConsoleSessionStore(context),
    tag = TAG
) {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Xiaomi MiMo"

    override suspend fun saveSession(cookies: Map<String, String>, email: String?) {
        // Mimo 使用小米账号登录，token可能在cookie中
        val token = cookies["token"]
            ?: cookies["session_id"]
            ?: cookies["mimo_token"]
            ?: cookies["xiaomi_token"]

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
        const val PROVIDER_ID = "mimo"
        private const val TAG = "MimoConsoleAuth"
        private const val SESSION_DURATION_MS = 48 * 3600 * 1000L // 48小时
    }
}
