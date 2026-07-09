package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

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
            id = computeId(apiKey),
            label = label.trim(),
            apiKey = apiKey.trim()
        )
        val accounts = getAccounts().toMutableList()
        // 同一 API Key 重复添加时，更新 label 而不是创建重复账户
        val existingIdx = accounts.indexOfFirst { it.id == account.id }
        if (existingIdx >= 0) {
            accounts[existingIdx] = accounts[existingIdx].copy(label = account.label)
        } else {
            accounts.add(account)
        }
        saveAccounts(accounts)
        return account
    }

    /**
     * 根据 API Key 计算确定性账户 ID（SHA-256 前 4 字节 → 8 位 hex）。
     * 删除后重新添加同一 Key 可恢复关联所有历史数据。
     */
    fun computeId(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
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
