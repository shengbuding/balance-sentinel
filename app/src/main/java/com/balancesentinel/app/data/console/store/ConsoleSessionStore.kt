package com.balancesentinel.app.data.console.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.balancesentinel.app.data.console.auth.ConsoleSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 控制台登录态加密存储
 */
@Deprecated("Use ConsoleStore instead. This store is unused by the UI layer.", ReplaceWith("ConsoleStore"))
class ConsoleSessionStore(
    private val context: Context,
    private val injectedPrefs: SharedPreferences? = null
) {
    private val prefs: SharedPreferences by lazy {
        injectedPrefs ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun saveSession(session: ConsoleSession) {
        prefs.edit()
            .putString("${KEY_SESSION_PREFIX}${session.providerId}", json.encodeToString(session))
            .apply()
    }

    fun getSession(providerId: String): ConsoleSession? {
        val raw = prefs.getString("${KEY_SESSION_PREFIX}$providerId", null) ?: return null
        return try {
            json.decodeFromString<ConsoleSession>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun isLoggedIn(providerId: String): Boolean {
        val session = getSession(providerId) ?: return false
        return session.isValid()
    }

    fun clearSession(providerId: String) {
        prefs.edit().remove("${KEY_SESSION_PREFIX}$providerId").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * 更新会话活跃时间
     */
    fun updateActiveTime(providerId: String) {
        val session = getSession(providerId) ?: return
        saveSession(session.updateActiveTime())
    }

    /**
     * 获取所有有效会话
     */
    fun getAllValidSessions(): List<ConsoleSession> {
        val allSessions = mutableListOf<ConsoleSession>()
        val allKeys = prefs.all.keys

        for (key in allKeys) {
            if (key.startsWith(KEY_SESSION_PREFIX)) {
                val providerId = key.removePrefix(KEY_SESSION_PREFIX)
                val session = getSession(providerId)
                if (session != null && session.isValid()) {
                    allSessions.add(session)
                }
            }
        }

        return allSessions
    }

    /**
     * 获取所有会话（包括过期的）
     */
    fun getAllSessions(): List<ConsoleSession> {
        val allSessions = mutableListOf<ConsoleSession>()
        val allKeys = prefs.all.keys

        for (key in allKeys) {
            if (key.startsWith(KEY_SESSION_PREFIX)) {
                val providerId = key.removePrefix(KEY_SESSION_PREFIX)
                val session = getSession(providerId)
                if (session != null) {
                    allSessions.add(session)
                }
            }
        }

        return allSessions
    }

    /**
     * 清理过期会话
     */
    fun cleanupExpiredSessions() {
        val allKeys = prefs.all.keys
        val editor = prefs.edit()
        var cleanedCount = 0

        for (key in allKeys) {
            if (key.startsWith(KEY_SESSION_PREFIX)) {
                val providerId = key.removePrefix(KEY_SESSION_PREFIX)
                val session = getSession(providerId)
                if (session != null && session.isExpired()) {
                    editor.remove(key)
                    cleanedCount++
                }
            }
        }

        if (cleanedCount > 0) {
            editor.apply()
        }
    }

    /**
     * 检查指定平台是否有有效会话
     */
    fun hasValidSession(providerId: String): Boolean {
        val session = getSession(providerId) ?: return false
        return session.isValid()
    }

    companion object {
        private const val PREFS_NAME = "console_session_prefs"
        private const val KEY_SESSION_PREFIX = "session_"
    }
}
