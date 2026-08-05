package com.balancesentinel.app.data.credentials

import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.Serializable

@Serializable
data class CredentialPayload(
    val accounts: List<AccountInfo>,
    val legacyApiKey: String? = null
) {
    fun validate() {
        require(accounts.all { it.id.isNotBlank() }) { "Account id must not be blank" }
        require(accounts.all { it.label.isNotBlank() }) { "Account label must not be blank" }
        require(accounts.all { it.apiKey.isNotBlank() }) { "Account API key must not be blank" }
        require(accounts.map { it.id }.distinct().size == accounts.size) { "Account ids must be unique" }
        require(legacyApiKey == null || legacyApiKey.isNotBlank()) { "Legacy API key must not be blank" }
    }
}

enum class CredentialGeneration {
    LEGACY,
    ENCRYPTED_PREFERENCES
}

sealed interface CredentialReadResult {
    data object Missing : CredentialReadResult
    data class Valid(
        val payload: CredentialPayload,
        val generation: CredentialGeneration
    ) : CredentialReadResult

    data class Corrupt(val exception: DataCorruptionException) : CredentialReadResult
}

class DataCorruptionException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
