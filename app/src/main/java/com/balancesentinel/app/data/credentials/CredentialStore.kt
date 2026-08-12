package com.balancesentinel.app.data.credentials

interface CredentialStore {
    fun read(): CredentialReadResult

    suspend fun write(payload: CredentialPayload)

    suspend fun clear()
}

/** Opaque recovery records stored in the same encrypted credential domain. */
interface ConfigImportRecoveryStore {
    fun readConfigImportManifest(operationId: String): String?

    fun listConfigImportManifestIds(): Set<String>

    suspend fun writeConfigImportManifest(operationId: String, manifest: String)

    suspend fun clearConfigImportManifest(operationId: String)
}
