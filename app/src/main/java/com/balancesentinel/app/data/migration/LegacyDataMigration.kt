package com.balancesentinel.app.data.migration

import androidx.room.withTransaction
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class LegacyDataMigration(
    private val database: WalletDatabase,
    private val source: LegacyDataSource,
    private val verifier: LegacyDataVerifier = LegacyDataVerifier(database),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onStage: (LegacyAccountMigrationStage) -> Unit = {}
) {
    companion object { const val BATCH_SIZE = 500; private const val READ_FAILURE_OPERATION = "legacy-data-read-failure" }

    suspend fun run(): LegacyDataVerification = withContext(Dispatchers.IO) {
        val snapshot = try {
            source.read()
        } catch (error: Exception) {
            recordReadFailure(error)
            throw error
        }
        if (snapshot.records.isEmpty() && snapshot.summaries.isEmpty() && snapshot.usage.isEmpty() && snapshot.logs.isEmpty()) {
            resetFailedMetadata()
            advanceMetadata(LegacyMigrationStage.DISCOVERED)
            return@withContext LegacyDataVerification(0, 0, 0, 0)
        }
        val operationId = operationId(snapshot)
        val manifest = ensureOperation(operationId, snapshot)
        val mappings = manifest.mappings
        try {
            resetFailedMetadata()
            advanceMetadata(LegacyMigrationStage.DISCOVERED)
            advanceMetadata(LegacyMigrationStage.VALIDATED)
            advanceMetadata(LegacyMigrationStage.CREDENTIALS_STAGED)
            writeRecords(operationId, snapshot.records, mappings)
            writeRemaining(operationId, snapshot, mappings)
            advanceMetadata(LegacyMigrationStage.ROOM_WRITTEN)
            val verification = verifier.verify(snapshot, mappings, operationId, manifest)
            advanceMetadata(LegacyMigrationStage.VERIFIED)
            publish(operationId)
            advanceMetadata(LegacyMigrationStage.ACTIVE, roomGeneration = true)
            onStage(LegacyAccountMigrationStage.ACTIVE)
            if (source.clear(snapshot)) {
                markCleaned(operationId)
                advanceMetadata(LegacyMigrationStage.CLEANED)
                onStage(LegacyAccountMigrationStage.CLEANED)
            }
            verification
        } catch (error: Exception) {
            fail(operationId, error)
            advanceMetadata(LegacyMigrationStage.FAILED)
            throw error
        }
    }

    private suspend fun ensureOperation(id: String, snapshot: LegacyDataSnapshot): LegacyMigrationManifest = database.withTransaction {
        database.mutationOperationDao().get(id)?.let {
            if (it.stage == MutationStage.FAILED) {
                require(database.mutationOperationDao().updateStage(id, MutationStage.PREPARED, it.batchCursor, null, null, now()) == 1)
            }
            return@withTransaction decodeManifest(it.targetsJson)
        }
        val accounts = database.accountDao().getAllForMigration()
        val mappings = accounts.mapNotNull { account -> account.legacyStorageId?.let { it to account.id } }.toMap()
        database.appMetadataDao().ensureSingleton(now())
        val metadata = requireNotNull(database.appMetadataDao().get())
        val manifest = LegacyMigrationManifest(
            mappings = mappings,
            baselineLegacyRecordCount = database.historyDao().countLegacyRecords(),
            baselineLegacyRecordMaxId = database.historyDao().maxLegacyRecordId(),
            expectedRecordCount = snapshot.records.size,
            expectedSummaryKeys = snapshot.summaries.map { summaryKey(it.date, mappings[it.accountId] ?: error("Missing mapping"), it.currency) },
            expectedUsageIds = snapshot.usage.map { usageId(it, mappings, id) },
            expectedLogIds = snapshot.logs.map { it.id }
        )
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = id,
                operationType = MutationOperationType.LEGACY_DATA_MIGRATION,
                targetsJson = Json.encodeToString(manifest),
                baselineRevision = metadata.localRevision,
                createdAt = now(), updatedAt = now()
            )
        )
        manifest
    }

    private suspend fun writeRecords(id: String, records: List<com.balancesentinel.app.data.model.RawRecord>, mappings: Map<String, String>) {
        val op = requireNotNull(database.mutationOperationDao().get(id))
        var cursor = op.batchCursor.toInt().coerceIn(0, records.size)
        while (cursor < records.size) {
            val end = minOf(cursor + BATCH_SIZE, records.size)
            val batch = records.subList(cursor, end).map { record ->
                BalanceRecordEntity(accountId = requireMapping(record.accountId, mappings), currency = record.currency.uppercase(), recordedAt = record.timestamp, totalBalance = record.totalBalance.toDouble(), grantedBalance = record.grantedBalance.toDouble(), toppedUpBalance = record.toppedUpBalance.toDouble(), source = BalanceRecordSource.LEGACY_MIGRATION)
            }
            database.withTransaction {
                if (batch.isNotEmpty()) database.historyDao().insertBalanceBatch(batch)
                updateOperation(id, MutationStage.ROOM_WRITTEN, end.toLong())
            }
            cursor = end
        }
    }

    private suspend fun writeRemaining(id: String, snapshot: LegacyDataSnapshot, mappings: Map<String, String>) = database.withTransaction {
        snapshot.summaries.chunked(BATCH_SIZE).forEach { batch -> database.historyDao().upsertSummaries(batch.map { s -> DailySummaryEntity(s.date, requireMapping(s.accountId, mappings), s.currency.uppercase(), s.open.toDouble(), s.close.toDouble(), s.consumed.toDouble(), s.toppedUp.toDouble(), s.granted.toDouble(), s.avgBalance.toDouble(), s.sampleCount, s.toppedUpBalanceClose.toDouble(), s.grantedBalanceClose.toDouble(), s.generatedAt) }) }
        snapshot.usage.forEach { u -> database.usageDao().upsertSnapshotWithRecords(com.balancesentinel.app.data.local.usage.UsageSnapshotEntity(id = usageId(u, mappings, id), accountId = requireMapping(u.accountId, mappings), capturedAt = u.timestamp, identityDiscriminator = "legacy-migration:$id"), u.records.mapIndexed { index, r -> com.balancesentinel.app.data.local.usage.UsageRecordEntity(usageId(u, mappings, id), index, r.model_name, r.total_tokens, r.prompt_tokens, r.completion_tokens) }) }
        snapshot.logs.chunked(BATCH_SIZE).forEach { batch -> batch.forEach { e -> if (database.eventLogDao().get(e.id) == null) database.eventLogDao().insertAll(listOf(EventLogEntity(id = e.id, eventType = EventLogType.valueOf(e.type.name), totalBalanceText = e.totalBalance, currencyText = e.currency, isAvailable = e.isAvailable, grantedBalanceText = e.grantedBalance, toppedUpBalanceText = e.toppedUpBalance, recordedAt = e.timestamp, message = e.message, intervalSeconds = e.intervalSeconds.takeIf { it != 0 }, expectedAt = e.expectedTime.takeIf { it != 0L }, alarmMethod = e.alarmMethod.takeIf { it.isNotEmpty() }, missReason = e.missReason.takeIf { it.isNotEmpty() }))) } }
        updateOperation(id, MutationStage.ROOM_WRITTEN, snapshot.records.size.toLong())
    }

    private suspend fun publish(id: String) { database.withTransaction { val current = requireNotNull(database.mutationOperationDao().get(id)); if (current.stage == MutationStage.PUBLISHED || current.stage == MutationStage.ACTIVE || current.stage == MutationStage.CLEANED) return@withTransaction; updateOperation(id, MutationStage.VERIFIED, current.batchCursor); require(database.mutationOperationDao().markPublished(id, now()) == 1) } }
    private suspend fun markCleaned(id: String) { require(database.mutationOperationDao().updateStage(id, MutationStage.CLEANED, requireNotNull(database.mutationOperationDao().get(id)).batchCursor, null, null, now()) == 1) }
    private suspend fun fail(id: String, error: Exception) { database.mutationOperationDao().updateStage(id, MutationStage.FAILED, requireNotNull(database.mutationOperationDao().get(id)).batchCursor, "MIGRATION_FAILED", error.message, now()) }
    private suspend fun updateOperation(id: String, stage: MutationStage, cursor: Long) { val current = requireNotNull(database.mutationOperationDao().get(id)); if (current.stage.ordinal < stage.ordinal || cursor > current.batchCursor) require(database.mutationOperationDao().updateStage(id, stage, cursor, null, null, now()) == 1) }
    private suspend fun advanceMetadata(stage: LegacyMigrationStage, roomGeneration: Boolean = false) { database.withTransaction { database.appMetadataDao().ensureSingleton(now()); val current = requireNotNull(database.appMetadataDao().get()); if (current.legacyMigrationStage.ordinal < stage.ordinal) require(database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(current.localRevision, current.activeDataGeneration, current.legacyMigrationStage, if (roomGeneration) "ROOM" else current.activeDataGeneration, stage, now()) == 1) } }
    private fun operationId(s: LegacyDataSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(s).toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return UUID.nameUUIDFromBytes(("wallet-sentinel:legacy-data:v2|$digest").toByteArray(StandardCharsets.UTF_8)).toString()
    }
    private fun decodeManifest(json: String): LegacyMigrationManifest = Json.decodeFromString(json)
    private fun summaryKey(date: String, accountId: String, currency: String) = "$date|$accountId|${currency.uppercase()}"
    private fun usageId(u: UsageSnapshot, mappings: Map<String, String>, operationId: String) = UUID.nameUUIDFromBytes("legacy|${mappings[u.accountId] ?: u.accountId}|${u.timestamp}|$operationId".toByteArray(StandardCharsets.UTF_8)).toString()
    private fun requireMapping(legacyId: String, mappings: Map<String, String>): String = mappings[legacyId]?.also { UUID.fromString(it) } ?: error("No stable account mapping for legacy id $legacyId")
    private suspend fun recordReadFailure(error: Exception) { database.withTransaction { database.appMetadataDao().ensureSingleton(now()); val existing = database.mutationOperationDao().get(READ_FAILURE_OPERATION); if (existing == null) { val metadata = requireNotNull(database.appMetadataDao().get()); database.mutationOperationDao().insertPrepared(MutationOperationEntity(READ_FAILURE_OPERATION, MutationOperationType.LEGACY_DATA_MIGRATION, targetsJson = "{}", baselineRevision = metadata.localRevision, createdAt = now(), updatedAt = now())) }; require(database.mutationOperationDao().updateStage(READ_FAILURE_OPERATION, MutationStage.FAILED, 0, "READ_FAILED", error.message, now()) == 1); val current = requireNotNull(database.appMetadataDao().get()); if (current.legacyMigrationStage != LegacyMigrationStage.FAILED) require(database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(current.localRevision, current.activeDataGeneration, current.legacyMigrationStage, current.activeDataGeneration, LegacyMigrationStage.FAILED, now()) == 1) } }
    private suspend fun resetFailedMetadata() { database.withTransaction { database.appMetadataDao().ensureSingleton(now()); if (database.appMetadataDao().get()?.legacyMigrationStage == LegacyMigrationStage.FAILED) require(database.appMetadataDao().resetFailedLegacyMigration(now()) == 1) } }
}
