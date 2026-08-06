package com.balancesentinel.app.data.migration

import androidx.room.withTransaction
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.util.UUID

fun interface LegacyAccountReader {
    fun read(): CredentialReadResult
}

data class LegacyAccountMigrationResult(
    val stage: LegacyAccountMigrationStage,
    val mappings: List<LegacyAccountMapping>
)

class LegacyAccountMigration(
    private val database: WalletDatabase,
    private val source: LegacyAccountReader,
    private val credentialStore: CredentialStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onStage: (LegacyAccountMigrationStage) -> Unit = {}
) {
    constructor(
        source: LegacyAccountReader,
        database: WalletDatabase,
        credentialStore: CredentialStore,
        now: () -> Long = { System.currentTimeMillis() }
    ) : this(database, source, credentialStore, now)

    suspend fun run(): LegacyAccountMigrationResult = withContext(Dispatchers.IO) {
        val payload = discoverAndValidate() ?: return@withContext LegacyAccountMigrationResult(
            LegacyAccountMigrationStage.DISCOVERED,
            emptyList()
        )
        val operationId = operationId(payload)
        val mappings = prepareOperation(operationId, payload)
        val mappingsByLegacyId = mappings.associateBy { it.legacyStorageId }
        val orderedMappings = payload.accounts.map { account ->
            requireNotNull(mappingsByLegacyId[account.id.trim()]) {
                "Migration manifest is missing legacy account ${account.id.trim()}"
            }
        }

        stageAndVerifyCredentials(payload)
        advanceOperation(operationId, MutationStage.CREDENTIALS_STAGED, orderedMappings.size.toLong())
        advanceMetadata(LegacyAccountMigrationStage.CREDENTIALS_STAGED)

        writePendingRows(operationId, payload, orderedMappings)
        advanceMetadata(LegacyAccountMigrationStage.ROOM_WRITTEN)

        verifyRowsAndLedgers(operationId, orderedMappings)
        onStage(LegacyAccountMigrationStage.VERIFIED)
        LegacyAccountMigrationResult(LegacyAccountMigrationStage.VERIFIED, orderedMappings)
    }

    private suspend fun discoverAndValidate(): CredentialPayload? {
        return when (val read = source.read()) {
            CredentialReadResult.Missing -> {
                advanceMetadata(LegacyAccountMigrationStage.DISCOVERED)
                null
            }
            is CredentialReadResult.Corrupt -> {
                advanceMetadata(LegacyAccountMigrationStage.FAILED)
                throw read.exception
            }
            is CredentialReadResult.Valid -> {
                advanceMetadata(LegacyAccountMigrationStage.DISCOVERED)
                read.payload.validate()
                advanceMetadata(LegacyAccountMigrationStage.VALIDATED)
                read.payload
            }
        }
    }

    private suspend fun prepareOperation(
        operationId: String,
        payload: CredentialPayload
    ): List<LegacyAccountMapping> = database.withTransaction {
        database.mutationOperationDao().get(operationId)?.let { existing ->
            return@withTransaction decodeMappings(existing.targetsJson)
        }
        database.appMetadataDao().ensureSingleton(now())
        val metadata = requireNotNull(database.appMetadataDao().get())
        val existingByLegacyStorageId = database.accountDao().getAllForMigration()
            .mapNotNull { account -> account.legacyStorageId?.let { it to account } }
            .toMap()
        val mappings = payload.accounts.map { account ->
            val legacyStorageId = account.id.trim()
            existingByLegacyStorageId[legacyStorageId]?.let { existing ->
                LegacyAccountMapping(
                    legacyStorageId = legacyStorageId,
                    accountId = existing.id,
                    credentialGeneration = existing.activeCredentialGeneration
                )
            } ?: run {
                val accountId = UUID.randomUUID().toString()
                LegacyAccountMapping(
                    legacyStorageId = legacyStorageId,
                    accountId = accountId,
                    credentialGeneration = "legacy:$operationId:$accountId"
                )
            }
        }
        val manifest = Json.encodeToString(mappings)
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = operationId,
                operationType = MutationOperationType.LEGACY_ACCOUNT_MIGRATION,
                targetsJson = manifest,
                stagedGenerationManifestJson = manifest,
                baselineRevision = metadata.localRevision,
                createdAt = now(),
                updatedAt = now()
            )
        )
        mappings
    }

    private suspend fun stageAndVerifyCredentials(payload: CredentialPayload) {
        credentialStore.write(payload)
        val readback = credentialStore.read()
        require(readback is CredentialReadResult.Valid) {
            "Staged legacy credentials are missing or corrupt"
        }
        require(readback.generation == CredentialGeneration.ENCRYPTED_PREFERENCES) {
            "Staged legacy credentials were read from the wrong generation"
        }
        require(readback.payload == payload) {
            "Staged legacy credential readback does not match the source"
        }
        readback.payload.validate()
    }

    private suspend fun writePendingRows(
        operationId: String,
        payload: CredentialPayload,
        mappings: List<LegacyAccountMapping>
    ) {
        database.withTransaction {
            payload.accounts.forEachIndexed { index, account ->
                val mapping = mappings[index]
                val existing = database.accountDao().get(mapping.accountId)
                if (existing == null) {
                    database.accountDao().insertCreate(
                        AccountEntity(
                            id = mapping.accountId,
                            displayOrder = index,
                            label = account.label.trim(),
                            providerType = account.providerType,
                            providerConfigJson = providerConfig(account),
                            activeCredentialGeneration = mapping.credentialGeneration,
                            state = AccountState.PENDING,
                            legacyStorageId = mapping.legacyStorageId,
                            createdAt = now(),
                            updatedAt = now()
                        )
                    )
                } else {
                    require(existing.legacyStorageId == mapping.legacyStorageId) {
                        "Persisted migration mapping does not match Room account ${mapping.accountId}"
                    }
                }
            }
            updateOperationInsideTransaction(operationId, MutationStage.ROOM_WRITTEN, mappings.size.toLong())
        }
    }

    private suspend fun verifyRowsAndLedgers(
        operationId: String,
        mappings: List<LegacyAccountMapping>
    ) {
        database.withTransaction {
            mappings.forEach { mapping ->
                val row = requireNotNull(database.accountDao().get(mapping.accountId))
                require(row.legacyStorageId == mapping.legacyStorageId)
                when (row.state) {
                    AccountState.PENDING -> markVerified(row.id)
                    AccountState.VERIFIED -> Unit
                }
            }
            updateOperationInsideTransaction(operationId, MutationStage.VERIFIED, mappings.size.toLong())
            advanceMetadataInsideTransaction(LegacyMigrationStage.VERIFIED)
        }
    }

    private suspend fun markVerified(accountId: String) {
        val statement = database.openHelper.writableDatabase.compileStatement(
            "UPDATE accounts SET state = 'VERIFIED', updated_at = ? WHERE id = ? AND state = 'PENDING'"
        )
        statement.bindLong(1, now())
        statement.bindString(2, accountId)
        require(statement.executeUpdateDelete() == 1)
        require(database.accountDao().get(accountId)?.state == AccountState.VERIFIED)
    }

    private suspend fun advanceOperation(operationId: String, stage: MutationStage, cursor: Long) {
        database.withTransaction {
            updateOperationInsideTransaction(operationId, stage, cursor)
        }
    }

    private suspend fun updateOperationInsideTransaction(
        operationId: String,
        stage: MutationStage,
        cursor: Long
    ) {
        val current = requireNotNull(database.mutationOperationDao().get(operationId))
        if (current.stage.ordinal < stage.ordinal) {
            require(database.mutationOperationDao().updateStage(operationId, stage, cursor, null, null, now()) == 1)
        }
    }

    private suspend fun advanceMetadata(stage: LegacyAccountMigrationStage) {
        val target = LegacyMigrationStage.valueOf(stage.name)
        var advanced = false
        database.withTransaction {
            database.appMetadataDao().ensureSingleton(now())
            val current = requireNotNull(database.appMetadataDao().get())
            if (current.legacyMigrationStage.ordinal < target.ordinal) {
                require(
                    database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
                        current.localRevision,
                        current.activeDataGeneration,
                        current.legacyMigrationStage,
                        current.activeDataGeneration,
                        target,
                        now()
                    ) == 1
                )
                advanced = true
            }
        }
        if (advanced) onStage(stage)
    }

    private suspend fun advanceMetadataInsideTransaction(target: LegacyMigrationStage) {
        database.appMetadataDao().ensureSingleton(now())
        val current = requireNotNull(database.appMetadataDao().get())
        if (current.legacyMigrationStage.ordinal < target.ordinal) {
            require(
                database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
                    current.localRevision,
                    current.activeDataGeneration,
                    current.legacyMigrationStage,
                    current.activeDataGeneration,
                    target,
                    now()
                ) == 1
            )
        }
    }

    private fun operationId(payload: CredentialPayload): String = UUID.nameUUIDFromBytes(
        ("wallet-sentinel:legacy-account-migration:v1|" +
            payload.accounts.map { it.id.trim() }.sorted().joinToString("|")).toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun decodeMappings(json: String): List<LegacyAccountMapping> =
        Json.decodeFromString(json)

    private fun providerConfig(account: AccountInfo): String = buildJsonObject {
        account.extraSettings.forEach { (key, value) -> put(key, value) }
        account.usageScript?.let { put("usageScript", it) }
        put("usageScriptEnabled", account.usageScriptEnabled)
        put("authorizedScriptOrigins", account.authorizedScriptOrigins.sorted().joinToString(","))
    }.toString()
}
