package com.balancesentinel.app.data.migration

import android.content.SharedPreferences
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.json.Json

class LegacyAccountSource(
    private val prefs: SharedPreferences,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
) {
    fun read(): CredentialReadResult = try {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return CredentialReadResult.Missing
        val payload = CredentialPayload(json.decodeFromString<List<AccountInfo>>(raw))
        payload.validate()
        CredentialReadResult.Valid(payload, CredentialGeneration.LEGACY)
    } catch (error: Exception) {
        CredentialReadResult.Corrupt(DataCorruptionException("Legacy accounts cannot be read", error))
    }

    companion object {
        const val KEY_ACCOUNTS = "accounts"
    }
}
