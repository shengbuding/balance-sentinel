package com.balancesentinel.app.data.credentials

interface CredentialStore {
    fun read(): CredentialReadResult

    fun write(payload: CredentialPayload)

    fun clear()
}
