package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class ApiKeyManager(
    private val appContext: Context,
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
        encodeDefaults = true
        explicitNulls = true
    }

    fun addAccount(
        label: String,
        apiKey: String,
        providerType: ProviderType = ProviderType.DEEPSEEK,
        extraSettings: Map<String, String> = emptyMap(),
        extraCredentials: Map<String, String> = emptyMap(),
        usageScript: String? = null
    ): AccountInfo {
        val draft = AccountDraft(
            label = label,
            apiKey = apiKey,
            providerType = providerType,
            extraCredentials = extraCredentials,
            extraSettings = extraSettings,
            usageScript = usageScript,
            usageScriptEnabled = true,
            authorizedScriptOrigins = emptySet()
        )
        return when (val result = saveAccount(computeId(apiKey), draft)) {
            is AccountSaveResult.Created -> result.account
            is AccountSaveResult.Updated -> result.account
            is AccountSaveResult.Replaced -> result.account
        }
    }

    fun saveAccount(existingId: String?, draft: AccountDraft): AccountSaveResult = mutateAccounts { accounts ->
        val normalizedDraft = draft.copy(
            label = draft.label.trim(),
            apiKey = draft.apiKey.trim(),
            extraCredentials = draft.extraCredentials.toMap(),
            extraSettings = draft.extraSettings.toMap(),
            authorizedScriptOrigins = draft.authorizedScriptOrigins.toSet()
        )
        val current = existingId?.let { id -> accounts.firstOrNull { it.id == id } }
        val newId = computeId(normalizedDraft.apiKey)
        val account = AccountInfo(
            id = newId,
            label = normalizedDraft.label,
            apiKey = normalizedDraft.apiKey,
            providerType = normalizedDraft.providerType,
            extraCredentials = normalizedDraft.extraCredentials,
            extraSettings = normalizedDraft.extraSettings,
            usageScript = normalizedDraft.usageScript,
            usageScriptEnabled = normalizedDraft.usageScriptEnabled,
            authorizedScriptOrigins = normalizedDraft.authorizedScriptOrigins,
            revision = current?.revision?.plus(1) ?: 0
        )

        when {
            current == null -> {
                accounts.removeAll { it.id == newId }
                accounts.add(account)
                AccountSaveResult.Created(account)
            }
            current.id == newId -> {
                accounts[accounts.indexOfFirst { it.id == current.id }] = account
                AccountSaveResult.Updated(current, account)
            }
            else -> {
                accounts.removeAll { it.id == current.id || it.id == newId }
                accounts.add(account)
                AccountSaveResult.Replaced(current, account)
            }
        }
    }

    fun replaceAll(newAccounts: List<AccountInfo>) {
        mutateAccounts { accounts ->
            accounts.clear()
            accounts.addAll(newAccounts)
        }
    }

    fun computeId(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

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

    fun getAccount(id: String): AccountInfo? = getAccounts().find { it.id == id }

    fun hasAccounts(): Boolean = getAccounts().isNotEmpty()

    fun clearAll() {
        synchronized(accountLock) {
            check(prefs.edit().remove(KEY_ACCOUNTS).commit())
        }
    }

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

    fun migrateAccountIds(): Map<String, String> = mutateAccounts { accounts ->
        val migrationMap = mutableMapOf<String, String>()
        for (index in accounts.indices) {
            val account = accounts[index]
            if (account.id.length != 16) {
                val newId = computeId(account.apiKey)
                migrationMap[account.id] = newId
                accounts[index] = account.copy(id = newId)
            }
        }
        migrationMap
    }

    private fun readAccountsLocked(): List<AccountInfo> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<AccountInfo>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
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
    }
}
