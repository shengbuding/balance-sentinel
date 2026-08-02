package com.balancesentinel.app.data.console.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.balancesentinel.app.ui.console.ConsolePlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 控制台统一存储
 * 管理平台配置和会话数据
 */
class ConsoleStore(
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

    // ═══════════════════════════════════════════════════════════
    // 平台管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取所有已添加的平台
     */
    fun getPlatforms(): List<ConsolePlatform> {
        val raw = prefs.getString(KEY_PLATFORMS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ConsolePlatform>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 添加平台
     */
    fun addPlatform(platform: ConsolePlatform) {
        val platforms = getPlatforms().toMutableList()
        if (platforms.none { it.id == platform.id }) {
            platforms.add(platform)
            prefs.edit().putString(KEY_PLATFORMS, json.encodeToString(platforms)).apply()
        }
    }

    /**
     * 删除平台（同时清除会话）
     */
    fun removePlatform(platformId: String) {
        // 删除平台配置
        val platforms = getPlatforms().filter { it.id != platformId }
        prefs.edit().putString(KEY_PLATFORMS, json.encodeToString(platforms)).apply()

        // 删除会话
        removeSession(platformId)
    }

    /**
     * 更新平台配置
     */
    fun updatePlatform(platform: ConsolePlatform) {
        val platforms = getPlatforms().toMutableList()
        val index = platforms.indexOfFirst { it.id == platform.id }
        if (index != -1) {
            platforms[index] = platform
            prefs.edit().putString(KEY_PLATFORMS, json.encodeToString(platforms)).apply()
        }
    }

    /**
     * 检查平台是否已添加
     */
    fun hasPlatform(platformId: String): Boolean {
        return getPlatforms().any { it.id == platformId }
    }

    // ═══════════════════════════════════════════════════════════
    // 会话管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 保存会话
     */
    fun saveSession(platformId: String, session: ConsoleSession) {
        prefs.edit()
            .putString("${KEY_SESSION_PREFIX}$platformId", json.encodeToString(session))
            .apply()
    }

    /**
     * 获取会话
     */
    fun getSession(platformId: String): ConsoleSession? {
        val raw = prefs.getString("${KEY_SESSION_PREFIX}$platformId", null) ?: return null
        return try {
            json.decodeFromString<ConsoleSession>(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun getValidSession(platformId: String, now: Long = System.currentTimeMillis()): ConsoleSession? {
        val session = getSession(platformId) ?: return null
        if (session.isValid(now)) return session
        removeSession(platformId)
        return null
    }

    /**
     * 删除会话
     */
    fun removeSession(platformId: String) {
        prefs.edit().remove("${KEY_SESSION_PREFIX}$platformId").apply()
    }

    /**
     * 检查是否有有效会话
     */
    fun hasValidSession(platformId: String): Boolean {
        return getValidSession(platformId) != null
    }

    /**
     * 清除所有数据
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "console_store_v3"
        private const val KEY_PLATFORMS = "platforms"
        private const val KEY_SESSION_PREFIX = "session_"
    }
}

/**
 * 会话数据
 * C6 修复：添加 30 天 TTL，不再永久有效
 */
@Serializable
data class ConsoleSession(
    val cookies: Map<String, String> = emptyMap(),
    val localStorage: Map<String, String> = emptyMap(),
    val token: String? = null,
    val email: String? = null,
    val loginTime: Long = System.currentTimeMillis(),
    val lastActiveTime: Long = System.currentTimeMillis()
) {
    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000 // 30 天
    }

    /**
     * 会话是否有效（30 天内活跃则有效）
     */
    fun isValid(): Boolean {
        return isValid(System.currentTimeMillis())
    }

    fun isValid(now: Long): Boolean {
        return now - lastActiveTime < THIRTY_DAYS_MS
    }

    /**
     * 更新最后活跃时间
     */
    fun updateActiveTime(): ConsoleSession = copy(
        lastActiveTime = System.currentTimeMillis()
    )
}
