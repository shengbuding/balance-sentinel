package com.balancesentinel.app.data.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedPreferencesCredentialStore(
    private val appContext: Context,
    private val injectedPrefs: SharedPreferences? = null
) : CredentialStore {

    private val prefs: SharedPreferences by lazy {
        injectedPrefs ?: run {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
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

    override fun read(): CredentialReadResult = try {
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return CredentialReadResult.Missing
        val payload = json.decodeFromString<CredentialPayload>(raw)
        payload.validate()
        CredentialReadResult.Valid(payload, CredentialGeneration.ENCRYPTED_PREFERENCES)
    } catch (error: Exception) {
        CredentialReadResult.Corrupt(DataCorruptionException("Credential payload cannot be read", error))
    }

    override fun write(payload: CredentialPayload) {
        payload.validate()
        requireWritable()
        check(prefs.edit().putString(KEY_PAYLOAD, json.encodeToString(payload)).commit())
    }

    override fun clear() {
        requireWritable()
        check(prefs.edit().remove(KEY_PAYLOAD).commit())
    }

    private fun requireWritable() {
        val result = read()
        if (result is CredentialReadResult.Corrupt) throw result.exception
    }

    private companion object {
        const val PREFS_NAME = "balance_sentinel_credentials"
        const val KEY_PAYLOAD = "credential_payload"
    }
}
