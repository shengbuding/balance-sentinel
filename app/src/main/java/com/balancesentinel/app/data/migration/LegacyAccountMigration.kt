package com.balancesentinel.app.data.migration

import androidx.room.withTransaction
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
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
    private val credentialStore: CredentialStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onStage: (LegacyAccountMigrationStage) -> Unit = {}
) {
    constructor(
        source: LegacyAccountReader,
        database: WalletDatabase,
        credentialStore: CredentialStore? = null,
        now: () -> Long = { System.currentTimeMillis() }
    ) : this(database, source, credentialStore, now)

    suspend fun run(): LegacyAccountMigrationResult = withContext(Dispatchers.IO) {
        val read = source.read()
        if (read is CredentialReadResult.Missing) {
            advance(LegacyAccountMigrationStage.DISCOVERED)
            return@withContext LegacyAccountMigrationResult(LegacyAccountMigrationStage.DISCOVERED, emptyList())
        }
        if (read is CredentialReadResult.Corrupt) {
            advance(LegacyAccountMigrationStage.FAILED)
            throw read.exception
        }
        val payload = (read as CredentialReadResult.Valid).payload
        advance(LegacyAccountMigrationStage.DISCOVERED)
        payload.validate()
        advance(LegacyAccountMigrationStage.VALIDATED)

        val operationId = UUID.nameUUIDFromBytes(
            ("wallet-sentinel:legacy-account-migration:v1|" +
                payload.accounts.map { it.id.trim() }.joinToString("|")).toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val mappings = payload.accounts.mapIndexed { index, account ->
            val legacyId = account.id.trim()
            val stableId = stableAccountId(legacyId)
            LegacyAccountMapping(legacyId, stableId, "legacy:$operationId:$stableId")
        }
        credentialStore?.write(payload)
        when (val staged = credentialStore?.read()) {
            is CredentialReadResult.Corrupt -> throw staged.exception
            is CredentialReadResult.Valid -> staged.payload.validate()
            else -> Unit
        }
        advance(LegacyAccountMigrationStage.CREDENTIALS_STAGED)

        database.withTransaction {
            val metadata = database.appMetadataDao().get()
            database.appMetadataDao().ensureSingleton(now())
            if (database.mutationOperationDao().get(operationId) == null) {
                database.mutationOperationDao().insertPrepared(
                    MutationOperationEntity(
                        id = operationId,
                        operationType = com.balancesentinel.app.data.local.mutation.MutationOperationType.LEGACY_ACCOUNT_MIGRATION,
                        baselineRevision = metadata?.localRevision ?: 0,
                        createdAt = now(),
                        updatedAt = now(),
                        targetsJson = buildJsonArray {
                            mappings.forEach { add(JsonPrimitive(it.accountId)) }
                        }.toString()
                    )
                )
            }
            payload.accounts.forEachIndexed { index, account ->
                val mapping = mappings[index]
                val existing = database.accountDao().get(mapping.accountId)
                    ?: database.accountDao().getAllForMigration().firstOrNull { it.legacyStorageId == mapping.legacyStorageId }
                if (existing == null) {
                    database.accountDao().insertCreate(
                        AccountEntity(
                            id = mapping.accountId,
                            displayOrder = index,
                            label = account.label.trim(),
                            providerType = account.providerType,
                            providerConfigJson = providerConfig(account),
                            activeCredentialGeneration = mapping.credentialGeneration,
                            state = AccountState.VERIFIED,
                            legacyStorageId = mapping.legacyStorageId,
                            createdAt = now(),
                            updatedAt = now()
                        )
                    )
                }
            }
            val operation = database.mutationOperationDao().get(operationId)
            if (operation?.stage?.ordinal ?: 0 < com.balancesentinel.app.data.local.mutation.MutationStage.ROOM_WRITTEN.ordinal) {
                database.mutationOperationDao().updateStage(operationId, com.balancesentinel.app.data.local.mutation.MutationStage.ROOM_WRITTEN, mappings.size.toLong(), null, null, now())
            }
            database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
                expectedRevision = metadata?.localRevision ?: 0,
                expectedActiveDataGeneration = metadata?.activeDataGeneration ?: "LEGACY",
                expectedLegacyMigrationStage = metadata?.legacyMigrationStage ?: LegacyMigrationStage.NONE,
                newActiveDataGeneration = "LEGACY",
                newLegacyMigrationStage = LegacyMigrationStage.ROOM_WRITTEN,
                updatedAt = now()
            )
        }
        advance(LegacyAccountMigrationStage.ROOM_WRITTEN)
        advance(LegacyAccountMigrationStage.VERIFIED)
        LegacyAccountMigrationResult(LegacyAccountMigrationStage.VERIFIED, mappings)
    }

    private suspend fun advance(stage: LegacyAccountMigrationStage) {
        val target = LegacyMigrationStage.valueOf(stage.name)
        database.appMetadataDao().ensureSingleton(now())
        val current = database.appMetadataDao().get() ?: return
        if (current.legacyMigrationStage.ordinal >= target.ordinal) return
        database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
            current.localRevision, current.activeDataGeneration, current.legacyMigrationStage,
            current.activeDataGeneration, target, now()
        )
        onStage(stage)
    }

    private fun providerConfig(account: com.balancesentinel.app.data.model.AccountInfo): String = buildJsonObject {
        account.extraSettings.forEach { (key, value) -> put(key, value) }
        account.usageScript?.let { put("usageScript", it) }
        put("usageScriptEnabled", account.usageScriptEnabled)
        put("authorizedScriptOrigins", account.authorizedScriptOrigins.sorted().joinToString(","))
    }.toString()

    companion object {
        fun stableAccountId(legacyStorageId: String): String = UUID.nameUUIDFromBytes(
            "wallet-sentinel:account:v1|${legacyStorageId.trim()}".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }
}
