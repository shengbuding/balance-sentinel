package com.balancesentinel.app.data.repository

import androidx.room.withTransaction
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.publication.AccountMutation
import com.balancesentinel.app.data.local.publication.AppSettingsWrite
import com.balancesentinel.app.data.local.publication.AccountAlertSettingsWrite
import com.balancesentinel.app.data.local.publication.AlertRuntimeStatesWrite
import com.balancesentinel.app.data.local.publication.MetadataPublication
import com.balancesentinel.app.data.local.publication.MutationPublication
import com.balancesentinel.app.data.local.publication.MutationPublisher
import com.balancesentinel.app.data.local.publication.NotificationSelectionsWrite
import com.balancesentinel.app.data.local.publication.SettingsPublication
import com.balancesentinel.app.data.local.publication.SnoozesWrite
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.AccountSaveResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** External cleanup is deliberately outside Room's transaction. */
fun interface AccountMutationCleanup {
    suspend fun clearGeneration(generation: String)

    companion object {
        val NO_OP = AccountMutationCleanup { }
    }
}

@Serializable
private data class AccountMutationManifest(
    val accountId: String,
    val legacyStorageId: String? = null,
    val previousGeneration: String? = null,
    val stagedGeneration: String,
    val expectedRevision: Long,
    val create: Boolean,
    val payloadFingerprint: String
)

/** Room-backed account mutation coordinator using the durable staged protocol. */
class RoomAccountMutationCoordinator(
    private val database: WalletDatabase,
    private val credentialStore: CredentialStore,
    private val cleanup: AccountMutationCleanup = AccountMutationCleanup.NO_OP,
    private val now: () -> Long = { System.currentTimeMillis() }
) : AccountMutationCoordinator, AccountMutationRecovery {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    override suspend fun save(
        existingId: String?,
        draft: AccountDraft
    ): AccountMutationResult = GLOBAL_MUTATION_LOCK.withLock {
        withContext(Dispatchers.IO) {
        val oldPayload = readPayloadOrEmpty(existingId == null)
        val existing = existingId?.let { id ->
            database.accountDao().get(id)
                ?: throw IllegalArgumentException("Account $id does not exist")
        }
        val before = existing?.let { findPayloadAccount(oldPayload, it) }
        val accountId = existing?.id ?: UUID.randomUUID().toString()
        val updated = before?.copy(
            label = draft.label.trim(),
            apiKey = draft.apiKey.trim(),
            providerType = draft.providerType,
            extraCredentials = draft.extraCredentials.toMap(),
            extraSettings = draft.extraSettings.toMap(),
            usageScript = draft.usageScript,
            usageScriptEnabled = draft.usageScriptEnabled,
            authorizedScriptOrigins = draft.authorizedScriptOrigins.toSet(),
            revision = before.revision + 1
        ) ?: AccountInfo(
            id = accountId,
            label = draft.label.trim(),
            apiKey = draft.apiKey.trim(),
            providerType = draft.providerType,
            extraCredentials = draft.extraCredentials.toMap(),
            extraSettings = draft.extraSettings.toMap(),
            usageScript = draft.usageScript,
            usageScriptEnabled = draft.usageScriptEnabled,
            authorizedScriptOrigins = draft.authorizedScriptOrigins.toSet()
        )
        val desiredPayload = oldPayload.copy(
            accounts = oldPayload.accounts
                .filterNot { account -> before != null && account.id == before.id }
                .plus(updated)
        )
        val operation = prepareOperation(
            operationType = MutationOperationType.ACCOUNT_REPLACE,
            accountId = accountId,
            legacyStorageId = before?.id,
            previousGeneration = existing?.activeCredentialGeneration,
            create = existing == null,
            payload = desiredPayload
        )
        try {
            stageCredentialsAndVerify(desiredPayload, operation)
            markStage(operation.id, MutationStage.VERIFIED)
            publish(operation, desiredPayload)
        } catch (failure: Exception) {
            rollbackBeforePublish(operation, oldPayload, failure)
            throw failure
        }
        finishCleanup(operation)
        val result = if (before == null) {
            AccountSaveResult.Created(updated)
        } else {
            AccountSaveResult.Updated(before, updated)
        }
            AccountMutationResult.Saved(result)
        }
    }

    override suspend fun delete(accountId: String): AccountMutationResult = GLOBAL_MUTATION_LOCK.withLock {
        withContext(Dispatchers.IO) {
        val oldPayload = readPayload()
        val existing = database.accountDao().get(accountId)
            ?: throw IllegalArgumentException("Account $accountId does not exist")
        val source = findPayloadAccount(oldPayload, existing)
        val desiredPayload = oldPayload.copy(accounts = oldPayload.accounts.filterNot { it.id == source.id })
        val operation = prepareOperation(
            operationType = MutationOperationType.ACCOUNT_DELETE,
            accountId = accountId,
            legacyStorageId = source.id,
            previousGeneration = existing.activeCredentialGeneration,
            create = false,
            payload = desiredPayload
        )
        try {
            stageCredentialsAndVerify(desiredPayload, operation)
            markStage(operation.id, MutationStage.VERIFIED)
            publish(operation, desiredPayload)
        } catch (failure: Exception) {
            rollbackBeforePublish(operation, oldPayload, failure)
            throw failure
        }
        finishCleanup(operation)
            AccountMutationResult.Deleted(accountId)
        }
    }

    override suspend fun recover(): AccountMutationResult.Recovered = GLOBAL_MUTATION_LOCK.withLock {
        withContext(Dispatchers.IO) {
            val recovered = mutableListOf<String>()
            database.mutationOperationDao().listRecoverable().forEach { operation ->
                if (operation.operationType != MutationOperationType.ACCOUNT_REPLACE &&
                    operation.operationType != MutationOperationType.ACCOUNT_DELETE
                ) {
                    return@forEach
                }
                try {
                    when (operation.stage) {
                MutationStage.PREPARED -> {
                    recoverPrepared(operation)
                }
                MutationStage.CREDENTIALS_STAGED,
                MutationStage.VERIFIED -> {
                    val payload = readPayloadOrNull()
                    val manifest = decodeManifest(operation)
                    if (payload == null || fingerprint(payload) != manifest.payloadFingerprint) {
                        cleanup.clearGeneration(manifest.stagedGeneration)
                        database.mutationOperationDao().updateStage(
                            operation.id,
                            MutationStage.FAILED,
                            operation.batchCursor,
                            "STAGED_PAYLOAD_MISSING",
                            "Staged credential payload did not pass recovery verification",
                            now()
                        )
                    } else {
                        if (operation.stage == MutationStage.CREDENTIALS_STAGED) {
                            markStage(operation.id, MutationStage.VERIFIED)
                        }
                        publish(operation, payload)
                        finishCleanup(operation)
                    }
                }
                MutationStage.PUBLISHED -> {
                    finishCleanup(operation)
                }
                else -> Unit
                    }
                    recovered += operation.id
                } catch (_: Exception) {
                    // A malformed or conflicting operation is isolated. The
                    // durable row remains recoverable for a later retry.
                }
            }
            AccountMutationResult.Recovered(recovered)
        }
    }

    private suspend fun prepareOperation(
        operationType: MutationOperationType,
        accountId: String,
        legacyStorageId: String?,
        previousGeneration: String?,
        create: Boolean,
        payload: CredentialPayload
    ): MutationOperationEntity = database.withTransaction {
        database.appMetadataDao().ensureSingleton(now())
        val metadata = requireNotNull(database.appMetadataDao().get())
        val operationId = UUID.randomUUID().toString()
        val stagedGeneration = "generation:$operationId:$accountId"
        val manifest = AccountMutationManifest(
            accountId = accountId,
            legacyStorageId = legacyStorageId,
            previousGeneration = previousGeneration,
            stagedGeneration = stagedGeneration,
            expectedRevision = database.accountDao().get(accountId)?.revision ?: 0,
            create = create,
            payloadFingerprint = fingerprint(payload)
        )
        val operation = MutationOperationEntity(
            id = operationId,
            operationType = operationType,
            stage = MutationStage.PREPARED,
            targetsJson = json.encodeToString(listOf(accountId)),
            stagedGenerationManifestJson = json.encodeToString(listOf(manifest)),
            baselineRevision = metadata.localRevision,
            createdAt = now(),
            updatedAt = now()
        )
        database.mutationOperationDao().insertPrepared(operation)
        operation
    }

    private suspend fun stageCredentialsAndVerify(
        desiredPayload: CredentialPayload,
        operation: MutationOperationEntity
    ) {
        credentialStore.write(desiredPayload)
        val readback = credentialStore.read()
        require(readback is CredentialReadResult.Valid) {
            "Staged credentials are missing or corrupt"
        }
        require(readback.generation == CredentialGeneration.ENCRYPTED_PREFERENCES) {
            "Staged credentials were read from the wrong generation"
        }
        require(readback.payload == desiredPayload) {
            "Staged credential readback does not match the requested payload"
        }
        readback.payload.validate()
        markStage(operation.id, MutationStage.CREDENTIALS_STAGED)
    }

    private suspend fun publish(operation: MutationOperationEntity, payload: CredentialPayload) {
        val manifest = decodeManifest(operation)
        val row = database.accountDao().get(manifest.accountId)
        val account = payload.accounts.firstOrNull { candidate ->
            candidate.id == manifest.legacyStorageId || candidate.id == manifest.accountId
        }
        val mutations = when (operation.operationType) {
            MutationOperationType.ACCOUNT_DELETE -> listOf(
                AccountMutation.Delete(manifest.accountId, manifest.expectedRevision)
            )
            MutationOperationType.ACCOUNT_REPLACE -> {
                requireNotNull(account) { "Published credential payload has no target account" }
                if (manifest.create) {
                    listOf(
                        AccountMutation.Create(
                            id = manifest.accountId,
                            displayOrder = payload.accounts.indexOf(account),
                            label = account.label,
                            providerType = account.providerType,
                            providerConfigJson = providerConfig(account),
                            activeCredentialGeneration = manifest.stagedGeneration
                        )
                    )
                } else {
                    require(row != null && row.state == AccountState.VERIFIED) {
                        "Account is no longer publishable"
                    }
                    listOf(
                        AccountMutation.Update(
                            id = manifest.accountId,
                            expectedRevision = manifest.expectedRevision,
                            displayOrder = row.displayOrder,
                            label = account.label,
                            providerType = account.providerType,
                            providerConfigJson = providerConfig(account),
                            activeCredentialGeneration = manifest.stagedGeneration
                        )
                    )
                }
            }
            else -> error("Unsupported account mutation type ${operation.operationType}")
        }
        MutationPublisher(database).publish(
            MutationPublication(
                operationId = operation.id,
                baselineRevision = operation.baselineRevision,
                accountMutations = mutations,
                settings = SettingsPublication(
                    appSettings = AppSettingsWrite.Unchanged,
                    accountAlertSettings = AccountAlertSettingsWrite.Unchanged,
                    notificationSelections = NotificationSelectionsWrite.Unchanged,
                    alertRuntimeStates = AlertRuntimeStatesWrite.Unchanged,
                    snoozes = SnoozesWrite.Unchanged
                ),
                metadata = MetadataPublication.Unchanged,
                publishedAt = now()
            )
        )
    }

    private suspend fun finishCleanup(operation: MutationOperationEntity) {
        val manifest = decodeManifest(operation)
        try {
            manifest.previousGeneration?.let { cleanup.clearGeneration(it) }
            require(database.mutationOperationDao().markCompleted(operation.id, now()) == 1) {
                "Mutation ${operation.id} could not be completed"
            }
        } catch (_: Throwable) {
            // PUBLISHED is intentionally retained for idempotent startup retry.
        }
    }

    private suspend fun rollbackBeforePublish(
        operation: MutationOperationEntity,
        oldPayload: CredentialPayload,
        failure: Throwable
    ) {
        var rollbackSucceeded = true
        try {
            credentialStore.write(oldPayload)
        } catch (_: Exception) {
            rollbackSucceeded = false
        }
        var cleanupSucceeded = true
        try {
            cleanup.clearGeneration(decodeManifest(operation).stagedGeneration)
        } catch (_: Exception) {
            cleanupSucceeded = false
        }
        database.mutationOperationDao().updateStage(
            operation.id,
            if (rollbackSucceeded && cleanupSucceeded) MutationStage.FAILED else MutationStage.PREPARED,
            operation.batchCursor,
            if (rollbackSucceeded && cleanupSucceeded) failure::class.simpleName else "ROLLBACK_PENDING",
            if (rollbackSucceeded && cleanupSucceeded) failure.message else "External rollback must be retried",
            now()
        )
    }

    private suspend fun recoverPrepared(operation: MutationOperationEntity) {
        val manifest = decodeManifest(operation)
        val payload = readPayloadOrNull()
        if (payload != null && fingerprint(payload) == manifest.payloadFingerprint) {
            markStage(operation.id, MutationStage.CREDENTIALS_STAGED)
            markStage(operation.id, MutationStage.VERIFIED)
            publish(operation, payload)
            finishCleanup(operation)
            return
        }
        try {
            cleanup.clearGeneration(manifest.stagedGeneration)
        } catch (_: Exception) {
            return
        }
        database.mutationOperationDao().updateStage(
            operation.id,
            MutationStage.FAILED,
            operation.batchCursor,
            "STAGED_PAYLOAD_MISSING",
            "No verifiable staged payload remained after interruption",
            now()
        )
    }

    private suspend fun markStage(id: String, stage: MutationStage) {
        database.withTransaction {
            val operation = requireNotNull(database.mutationOperationDao().get(id))
            if (operation.stage.ordinal < stage.ordinal) {
                require(
                    database.mutationOperationDao().updateStage(
                        id, stage, operation.batchCursor, null, null, now()
                    ) == 1
                )
            }
        }
    }

    private suspend fun readPayload(): CredentialPayload = readPayloadOrNull()
        ?: error("Credentials are missing")

    private suspend fun readPayloadOrEmpty(allowMissing: Boolean): CredentialPayload =
        when (val result = credentialStore.read()) {
            CredentialReadResult.Missing -> {
                check(allowMissing) { "Credentials are missing" }
                CredentialPayload(emptyList())
            }
            is CredentialReadResult.Corrupt -> throw result.exception
            is CredentialReadResult.Valid -> {
                require(result.generation == CredentialGeneration.ENCRYPTED_PREFERENCES) {
                    "Active credential generation is not writable"
                }
                result.payload.also { it.validate() }
            }
        }

    private suspend fun readPayloadOrNull(): CredentialPayload? = when (val result = credentialStore.read()) {
        CredentialReadResult.Missing -> null
        is CredentialReadResult.Corrupt -> throw result.exception
        is CredentialReadResult.Valid -> {
            require(result.generation == CredentialGeneration.ENCRYPTED_PREFERENCES) {
                "Active credential generation is not writable"
            }
            result.payload.also { it.validate() }
        }
    }

    private suspend fun findPayloadAccount(
        payload: CredentialPayload,
        row: com.balancesentinel.app.data.local.account.AccountEntity
    ): AccountInfo {
        return payload.accounts.firstOrNull { it.id == row.legacyStorageId || it.id == row.id }
            ?: error("Credential payload has no account ${row.id}")
    }

    private fun decodeManifest(operation: MutationOperationEntity): AccountMutationManifest =
        json.decodeFromString<List<AccountMutationManifest>>(
            operation.stagedGenerationManifestJson
        ).single()

    private fun fingerprint(payload: CredentialPayload): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun providerConfig(account: AccountInfo): String = buildJsonObject {
        account.extraSettings.forEach { (key, value) -> put(key, value) }
        account.usageScript?.let { put("usageScript", it) }
        put("usageScriptEnabled", account.usageScriptEnabled)
        put("authorizedScriptOrigins", account.authorizedScriptOrigins.sorted().joinToString(","))
    }.toString()

    private companion object {
        val GLOBAL_MUTATION_LOCK = Mutex()
    }
}
