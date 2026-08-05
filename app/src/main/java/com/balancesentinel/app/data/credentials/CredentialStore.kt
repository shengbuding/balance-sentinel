package com.balancesentinel.app.data.credentials

interface CredentialStore {
    fun read(): CredentialReadResult

    suspend fun write(payload: CredentialPayload)

    suspend fun clear()
}
