package com.balancesentinel.app.data.repository

import androidx.room.withTransaction
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.AccountSaveResult

/** External cleanup is deliberately outside Room's transaction. */
fun interface AccountMutationCleanup {
    suspend fun clearGeneration(generation: String)

    companion object {
        val NO_OP = AccountMutationCleanup { }
    }
}

/**
 * Initial account mutation seam. The RED phase intentionally retains the
 * legacy-only behavior; the GREEN implementation adds the durable protocol.
 */
class RoomAccountMutationCoordinator(
    private val database: WalletDatabase,
    private val credentialStore: CredentialStore,
    private val cleanup: AccountMutationCleanup = AccountMutationCleanup.NO_OP,
    private val now: () -> Long = { System.currentTimeMillis() }
) : AccountMutationCoordinator, AccountMutationRecovery {
    override suspend fun save(
        existingId: String?,
        draft: AccountDraft
    ): AccountMutationResult {
        val oldPayload = readPayload()
        val accounts = oldPayload.accounts.toMutableList()
        val existing = existingId?.let { id ->
            database.accountDao().get(id)
                ?: throw IllegalArgumentException("Account $id does not exist")
        }
        val sourceIndex = existing?.legacyStorageId?.let { legacyId ->
            accounts.indexOfFirst { it.id == legacyId }
        } ?: -1
        val before = accounts.getOrNull(sourceIndex)
        val updated = if (before == null) {
            AccountInfo(
                id = legacyIdFor(draft.apiKey),
                label = draft.label.trim(),
                apiKey = draft.apiKey.trim(),
                providerType = draft.providerType,
                extraCredentials = draft.extraCredentials.toMap(),
                extraSettings = draft.extraSettings.toMap(),
                usageScript = draft.usageScript,
                usageScriptEnabled = draft.usageScriptEnabled,
                authorizedScriptOrigins = draft.authorizedScriptOrigins.toSet()
            )
        } else {
            before.copy(
                label = draft.label.trim(),
                apiKey = draft.apiKey.trim(),
                providerType = draft.providerType,
                extraCredentials = draft.extraCredentials.toMap(),
                extraSettings = draft.extraSettings.toMap(),
                usageScript = draft.usageScript,
                usageScriptEnabled = draft.usageScriptEnabled,
                authorizedScriptOrigins = draft.authorizedScriptOrigins.toSet(),
                revision = before.revision + 1
            )
        }
        if (sourceIndex >= 0) accounts[sourceIndex] = updated else accounts += updated
        credentialStore.write(oldPayload.copy(accounts = accounts))
        return AccountMutationResult.Saved(
            if (before == null) AccountSaveResult.Created(updated)
            else AccountSaveResult.Updated(before, updated)
        )
    }

    override suspend fun delete(accountId: String): AccountMutationResult {
        val oldPayload = readPayload()
        val row = database.accountDao().get(accountId)
            ?: throw IllegalArgumentException("Account $accountId does not exist")
        val legacyId = row.legacyStorageId
        val accounts = oldPayload.accounts.filterNot { it.id == legacyId }
        credentialStore.write(oldPayload.copy(accounts = accounts))
        cleanup.clearGeneration(row.activeCredentialGeneration)
        return AccountMutationResult.Deleted(accountId)
    }

    override suspend fun recover(): AccountMutationResult.Recovered =
        AccountMutationResult.Recovered(emptyList())

    private fun readPayload(): CredentialPayload = when (val result = credentialStore.read()) {
        CredentialReadResult.Missing -> throw IllegalStateException("Credentials are missing")
        is CredentialReadResult.Corrupt -> throw result.exception
        is CredentialReadResult.Valid -> result.payload.also { it.validate() }
    }

    private fun legacyIdFor(apiKey: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(apiKey.trim().toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
}
