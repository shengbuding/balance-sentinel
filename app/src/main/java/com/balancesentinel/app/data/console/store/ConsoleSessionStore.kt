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

    companion object {
        private const val PREFS_NAME = "console_session_prefs"
        private const val KEY_SESSION_PREFIX = "session_"
    }
}
