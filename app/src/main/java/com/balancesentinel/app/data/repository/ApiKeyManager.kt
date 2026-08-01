package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 使用 EncryptedSharedPreferences 安全存储多组 API Key。
 * 存储 JSON 序列化的 List<AccountInfo>，每组包含 id/label/apiKey。
 */
class ApiKeyManager(
    private val appContext: Context,
    // test-only: inject SharedPreferences to bypass EncryptedSharedPreferences (unavailable in Robolectric)
    private val injectedPrefs: SharedPreferences? = null
) {
    private val accountLock = ACCOUNT_LOCK

    private val prefs: SharedPreferences by lazy {
        injectedPrefs ?: run {
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
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true  // 确保默认值被序列化
        explicitNulls = true   // 确保null值被序列化
    }

    // ── 多账户操作 ──

    fun addAccount(
        label: String,
        apiKey: String,
        providerType: ProviderType = ProviderType.DEEPSEEK,
        extraSettings: Map<String, String> = emptyMap(),
        extraCredentials: Map<String, String> = emptyMap(),
        usageScript: String? = null
    ): AccountInfo = mutateAccounts { accounts ->
        val id = computeId(apiKey)
        val existingIndex = accounts.indexOfFirst { it.id == id }
        val account = AccountInfo(
            id = id,
            label = label.trim(),
            apiKey = apiKey.trim(),
            providerType = providerType,
            extraCredentials = extraCredentials.toMap(),
            extraSettings = extraSettings.toMap(),
            usageScript = usageScript,
            revision = accounts.getOrNull(existingIndex)?.revision?.plus(1) ?: 0
        )
        if (existingIndex >= 0) accounts[existingIndex] = account else accounts.add(account)
        account
    }

    /**
     * 原子替换所有账户（用于配置导入）
     * C5+H10 修复：一次性写入，避免 clearAll + 逐个 add 的崩溃风险
     */
    fun replaceAll(newAccounts: List<AccountInfo>) {
        mutateAccounts { accounts ->
            accounts.clear()
            accounts.addAll(newAccounts)
        }
    }

    /**
     * 根据 API Key 计算确定性账户 ID（SHA-256 前 8 字节 → 16 位 hex）。
     * 删除后重新添加同一 Key 可恢复关联所有历史数据。
     */
    fun computeId(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算旧版ID（SHA-256 前 4 字节 → 8 位 hex）
     * 用于数据迁移
     */
    fun computeLegacyId(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun removeAccount(id: String) {
        mutateAccounts { accounts -> accounts.removeAll { it.id == id } }
    }

    fun renameAccount(id: String, newLabel: String) {
        mutateAccounts { accounts ->
            val index = accounts.indexOfFirst { it.id == id }
            if (index >= 0) {
                accounts[index] = accounts[index].copy(
                    label = newLabel.trim(),
                    revision = accounts[index].revision + 1
                )
            }
        }
    }

    fun updateExtraSettings(id: String, extraSettings: Map<String, String>) {
        mutateAccounts { accounts ->
            val index = accounts.indexOfFirst { it.id == id }
            if (index >= 0) {
                accounts[index] = accounts[index].copy(
                    extraSettings = extraSettings.toMap(),
                    revision = accounts[index].revision + 1
                )
            }
        }
    }

    fun updateUsageScript(id: String, usageScript: String?) {
        mutateAccounts { accounts ->
            val index = accounts.indexOfFirst { it.id == id }
            if (index >= 0) {
                accounts[index] = accounts[index].copy(
                    usageScript = usageScript,
                    revision = accounts[index].revision + 1
                )
            }
        }
    }

    fun getAccounts(): List<AccountInfo> = synchronized(accountLock) { readAccountsLocked() }

    private fun readAccountsLocked(): List<AccountInfo> {
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
        synchronized(accountLock) {
            check(prefs.edit().remove(KEY_ACCOUNTS).commit())
        }
    }

    // ── 兼容旧版单 Key 迁移 ──

    /**
     * 如果旧版单 Key 存在且没有账户数据，自动迁移为账户列表。
     */
    fun migrateLegacyKeyIfNeeded() {
        synchronized(accountLock) {
            if (readAccountsLocked().isNotEmpty()) return
            val legacyKey = prefs.getString(KEY_LEGACY_API_KEY, null)
            if (!legacyKey.isNullOrBlank()) {
                val account = AccountInfo(
                    id = computeId(legacyKey),
                    label = appContext.getString(R.string.default_account_label),
                    apiKey = legacyKey.trim()
                )
                check(
                    prefs.edit()
                        .putString(KEY_ACCOUNTS, json.encodeToString(listOf(account)))
                        .remove(KEY_LEGACY_API_KEY)
                        .commit()
                )
            }
        }
    }

    /**
     * 迁移旧版ID（4字节）到新版ID（8字节）
     * 返回迁移映射表：oldId -> newId
     */
    fun migrateAccountIds(): Map<String, String> = mutateAccounts { accounts ->
        val migrationMap = mutableMapOf<String, String>()
        for (index in accounts.indices) {
            val account = accounts[index]
            val newId = computeId(account.apiKey)
            if (account.id.length != 16) {
                migrationMap[account.id] = newId
                accounts[index] = account.copy(id = newId)
            }
        }
        migrationMap
    }

    private inline fun <T> mutateAccounts(block: (MutableList<AccountInfo>) -> T): T =
        synchronized(accountLock) {
            val accounts = readAccountsLocked().toMutableList()
            val result = block(accounts)
            check(prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(accounts)).commit())
            result
        }

    companion object {
        private val ACCOUNT_LOCK = Any()
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_LEGACY_API_KEY = "deepseek_api_key"
        private const val KEY_ID_MIGRATION_DONE = "id_migration_v2_done"
    }
}
