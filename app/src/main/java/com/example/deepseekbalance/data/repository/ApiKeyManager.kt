package com.example.deepseekbalance.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.deepseekbalance.R
import com.example.deepseekbalance.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 使用 EncryptedSharedPreferences 安全存储多组 API Key。
 * 存储 JSON 序列化的 List<AccountInfo>，每组包含 id/label/apiKey。
 */
class ApiKeyManager(private val appContext: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "deepseek_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── 多账户操作 ──

    fun addAccount(label: String, apiKey: String): AccountInfo {
        val account = AccountInfo(
            id = UUID.randomUUID().toString().take(8),
            label = label.trim(),
            apiKey = apiKey.trim()
        )
        val accounts = getAccounts().toMutableList()
        accounts.add(account)
        saveAccounts(accounts)
        return account
    }

    fun removeAccount(id: String) {
        val accounts = getAccounts().filter { it.id != id }
        saveAccounts(accounts)
    }

    fun renameAccount(id: String, newLabel: String) {
        val accounts = getAccounts().map {
            if (it.id == id) it.copy(label = newLabel.trim()) else it
        }
        saveAccounts(accounts)
    }

    fun getAccounts(): List<AccountInfo> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<AccountInfo>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getAccount(id: String): AccountInfo? {
        return getAccounts().find { it.id == id }
    }

    fun hasAccounts(): Boolean {
        return getAccounts().isNotEmpty()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_ACCOUNTS).apply()
    }

    private fun saveAccounts(accounts: List<AccountInfo>) {
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(accounts)).apply()
    }

    // ── 兼容旧版单 Key 迁移 ──

    /**
     * 如果旧版单 Key 存在且没有账户数据，自动迁移为账户列表。
     */
    fun migrateLegacyKeyIfNeeded() {
        if (getAccounts().isNotEmpty()) return
        val legacyKey = prefs.getString(KEY_LEGACY_API_KEY, null)
        if (!legacyKey.isNullOrBlank()) {
            addAccount(appContext.getString(R.string.default_account_label), legacyKey)
            prefs.edit().remove(KEY_LEGACY_API_KEY).apply()
        }
    }

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_LEGACY_API_KEY = "deepseek_api_key"
    }
}
