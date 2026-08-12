package com.balancesentinel.app.data.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.balancesentinel.app.data.repository.DataMutationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedPreferencesCredentialStore(
    private val appContext: Context,
    private val injectedPrefs: SharedPreferences? = null
) : CredentialStore, ConfigImportRecoveryStore {

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

    override suspend fun write(payload: CredentialPayload) = withContext(Dispatchers.IO) {
        DataMutationCoordinator.withMutation {
            payload.validate()
            requireWritable()
            check(prefs.edit().putString(KEY_PAYLOAD, json.encodeToString(payload)).commit()) {
                "Credential payload write commit failed"
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        DataMutationCoordinator.withMutation {
            requireWritable()
            val editor = prefs.edit().remove(KEY_PAYLOAD)
            prefs.all.keys
                .filter { it.startsWith(CONFIG_IMPORT_MANIFEST_PREFIX) }
                .forEach(editor::remove)
            check(editor.commit()) {
                "Credential payload clear commit failed"
            }
        }
    }

    override fun readConfigImportManifest(operationId: String): String? =
        prefs.getString(configImportManifestKey(operationId), null)

    override fun listConfigImportManifestIds(): Set<String> = prefs.all.keys
        .filter { it.startsWith(CONFIG_IMPORT_MANIFEST_PREFIX) }
        .mapTo(mutableSetOf()) { it.removePrefix(CONFIG_IMPORT_MANIFEST_PREFIX) }

    override suspend fun writeConfigImportManifest(operationId: String, manifest: String) =
        withContext(Dispatchers.IO) {
            DataMutationCoordinator.withMutation {
                requireWritable()
                check(
                    prefs.edit()
                        .putString(configImportManifestKey(operationId), manifest)
                        .commit()
                ) { "Configuration import recovery manifest write failed" }
            }
        }

    override suspend fun clearConfigImportManifest(operationId: String) =
        withContext(Dispatchers.IO) {
            DataMutationCoordinator.withMutation {
                requireWritable()
                check(prefs.edit().remove(configImportManifestKey(operationId)).commit()) {
                    "Configuration import recovery manifest clear failed"
                }
            }
        }

    private fun configImportManifestKey(operationId: String): String {
        require(operationId.isNotBlank() && operationId.length <= 128) {
            "Configuration import operation id is invalid"
        }
        return CONFIG_IMPORT_MANIFEST_PREFIX + operationId
    }

    private fun requireWritable() {
        val result = read()
        if (result is CredentialReadResult.Corrupt) throw result.exception
    }

    private companion object {
        const val PREFS_NAME = "balance_sentinel_credentials"
        const val KEY_PAYLOAD = "credential_payload"
        const val CONFIG_IMPORT_MANIFEST_PREFIX = "config_import_manifest:"
    }
}
