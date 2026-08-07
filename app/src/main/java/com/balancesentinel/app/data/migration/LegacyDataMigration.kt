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
import com.balancesentinel.app.data.local.usage.UsageRecordEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class LegacyDataMigration(
    private val database: WalletDatabase,
    private val source: LegacyDataSource,
    private val verifier: LegacyDataVerifier = LegacyDataVerifier(database),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onStage: (LegacyAccountMigrationStage) -> Unit = {}
) {
    companion object {
        const val BATCH_SIZE = 500
        private const val MANIFEST_VERSION = 2
        private const val READ_FAILURE_OPERATION = "legacy-data-read-failure"
    }

    suspend fun run(): LegacyDataVerification = withContext(Dispatchers.IO) {
        val snapshot = try {
            source.read()
        } catch (error: Exception) {
            recordReadFailure(error)
            throw error
        }
        if (snapshot.isEmpty()) {
            resetFailedMetadata()
            advanceMetadata(LegacyMigrationStage.DISCOVERED)
            return@withContext LegacyDataVerification(0, 0, 0, 0)
        }

        val operationId = operationId(snapshot)
        try {
            val manifest = ensureOperation(operationId, snapshot)
            val mappings = manifest.mappings
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
            failIfPresent(operationId, error)
            advanceMetadata(LegacyMigrationStage.FAILED)
            throw error
        }
    }

    private suspend fun ensureOperation(
        id: String,
        snapshot: LegacyDataSnapshot
    ): LegacyMigrationManifest = database.withTransaction {
        database.mutationOperationDao().get(id)?.let { existing ->
            val stored = decodeStoredManifest(existing.targetsJson, existing.manifestVersion)
            val manifest = if (
                existing.manifestVersion == MANIFEST_VERSION &&
                stored.version == MANIFEST_VERSION
            ) {
                stored
            } else {
                val upgraded = createManifest(snapshot, stored.mappings, id)
                require(
                    database.mutationOperationDao().upgradeLegacyDataManifestIfCurrent(
                        id = id,
                        expectedTargetsJson = existing.targetsJson,
                        expectedManifestVersion = existing.manifestVersion,
                        expectedStage = existing.stage,
                        expectedBatchCursor = existing.batchCursor,
                        newTargetsJson = Json.encodeToString(upgraded),
                        newManifestVersion = MANIFEST_VERSION,
                        updatedAt = now()
                    ) == 1
                ) { "Legacy migration manifest changed concurrently" }
                upgraded
            }

            val current = requireNotNull(database.mutationOperationDao().get(id))
            if (current.stage == MutationStage.FAILED) {
                require(
                    database.mutationOperationDao().updateStage(
                        id,
                        MutationStage.PREPARED,
                        current.batchCursor,
                        null,
                        null,
                        now()
                    ) == 1
                )
            }
            return@withTransaction manifest
        }

        val accounts = database.accountDao().getAllForMigration()
        val mappings = accounts.mapNotNull { account ->
            account.legacyStorageId?.let { it to account.id }
        }.toMap()
        database.appMetadataDao().ensureSingleton(now())
        val metadata = requireNotNull(database.appMetadataDao().get())
        val manifest = createManifest(snapshot, mappings, id)
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = id,
                operationType = MutationOperationType.LEGACY_DATA_MIGRATION,
                targetsJson = Json.encodeToString(manifest),
                manifestVersion = MANIFEST_VERSION,
                baselineRevision = metadata.localRevision,
                createdAt = now(),
                updatedAt = now()
            )
        )
        manifest
    }

    private suspend fun createManifest(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String
    ): LegacyMigrationManifest {
        validateMappings(snapshot, mappings)
        return LegacyMigrationManifest(
            version = MANIFEST_VERSION,
            mappings = mappings,
            baselineLegacyRecordCount = database.historyDao().countLegacyRecords(),
            baselineLegacyRecordMaxId = database.historyDao().maxLegacyRecordId(),
            baselineSummaryCount = database.historyDao().countSummaries(),
            baselineUsageCount = database.usageDao().countSnapshots(),
            baselineLogCount = database.eventLogDao().countLogs(),
            expectedRecordCount = snapshot.records.size,
            expectedSummaryCount = snapshot.summaries.size,
            expectedUsageCount = snapshot.usage.size,
            expectedUsageRecordCount = snapshot.usage.sumOf { it.records.size },
            expectedLogCount = snapshot.logs.size,
            expectedSummaryKeys = snapshot.summaries.map {
                summaryKey(it.date, requireMapping(it.accountId, mappings), it.currency)
            },
            expectedUsageIds = snapshot.usage.mapIndexed { ordinal, usage ->
                usageId(usage, mappings, operationId, ordinal)
            },
            expectedLogIds = snapshot.logs.map { it.id }
        )
    }

    private fun decodeStoredManifest(json: String, manifestVersion: Int): LegacyMigrationManifest {
        if (manifestVersion >= MANIFEST_VERSION) {
            return Json.decodeFromString<LegacyMigrationManifest>(json).also {
                require(it.version == MANIFEST_VERSION) { "Unsupported legacy data manifest version ${it.version}" }
            }
        }
        val root = Json.parseToJsonElement(json).jsonObject
        val mappings = if (root["mappings"] is JsonObject) {
            Json.decodeFromString<LegacyMigrationManifest>(json).mappings
        } else {
            Json.decodeFromString<Map<String, String>>(json)
        }
        return LegacyMigrationManifest(mappings = mappings)
    }

    private suspend fun writeRecords(
        id: String,
        records: List<com.balancesentinel.app.data.model.RawRecord>,
        mappings: Map<String, String>
    ) {
        val operation = requireNotNull(database.mutationOperationDao().get(id))
        var cursor = operation.batchCursor.toInt().coerceIn(0, records.size)
        while (cursor < records.size) {
            val end = minOf(cursor + BATCH_SIZE, records.size)
            val batch = records.subList(cursor, end).mapIndexed { index, record ->
                BalanceRecordEntity(
                    accountId = requireMapping(record.accountId, mappings),
                    currency = record.currency.uppercase(Locale.ROOT),
                    recordedAt = record.timestamp,
                    totalBalance = record.totalBalance.toDouble(),
                    grantedBalance = record.grantedBalance.toDouble(),
                    toppedUpBalance = record.toppedUpBalance.toDouble(),
                    source = BalanceRecordSource.LEGACY_MIGRATION,
                    migrationOperationId = id,
                    migrationSourceOrdinal = cursor + index
                )
            }
            database.withTransaction {
                database.historyDao().upsertMigrationBalanceBatch(batch)
                updateOperation(id, MutationStage.ROOM_WRITTEN, end.toLong())
            }
            cursor = end
        }
    }

    private suspend fun writeRemaining(
        id: String,
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>
    ) = database.withTransaction {
        snapshot.summaries.withIndex().chunked(BATCH_SIZE).forEach { batch ->
            database.historyDao().upsertSummaries(
                batch.map { indexed ->
                    val summary = indexed.value
                    DailySummaryEntity(
                        date = summary.date,
                        accountId = requireMapping(summary.accountId, mappings),
                        currency = summary.currency.uppercase(Locale.ROOT),
                        openBalance = summary.open.toDouble(),
                        closeBalance = summary.close.toDouble(),
                        consumedBalance = summary.consumed.toDouble(),
                        toppedUpBalance = summary.toppedUp.toDouble(),
                        grantedBalance = summary.granted.toDouble(),
                        averageBalance = summary.avgBalance.toDouble(),
                        sampleCount = summary.sampleCount,
                        toppedUpBalanceClose = summary.toppedUpBalanceClose.toDouble(),
                        grantedBalanceClose = summary.grantedBalanceClose.toDouble(),
                        generatedAt = summary.generatedAt,
                        migrationOperationId = id,
                        migrationSourceOrdinal = indexed.index
                    )
                }
            )
        }
        snapshot.usage.forEachIndexed { ordinal, usage ->
            val snapshotId = usageId(usage, mappings, id, ordinal)
            database.usageDao().upsertSnapshotWithRecords(
                UsageSnapshotEntity(
                    id = snapshotId,
                    accountId = requireMapping(usage.accountId, mappings),
                    capturedAt = usage.timestamp,
                    identityDiscriminator = "legacy-migration:$id:$ordinal",
                    migrationOperationId = id,
                    migrationSourceOrdinal = ordinal
                ),
                usage.records.mapIndexed { recordOrdinal, record ->
                    UsageRecordEntity(
                        snapshotId = snapshotId,
                        recordOrdinal = recordOrdinal,
                        modelName = record.model_name,
                        totalTokens = record.total_tokens,
                        promptTokens = record.prompt_tokens,
                        completionTokens = record.completion_tokens
                    )
                }
            )
        }
        snapshot.logs.withIndex().chunked(BATCH_SIZE).forEach { batch ->
            database.eventLogDao().insertMigrationEntries(
                batch.map { indexed ->
                    val entry = indexed.value
                    EventLogEntity(
                        eventType = EventLogType.valueOf(entry.type.name),
                        totalBalanceText = entry.totalBalance,
                        currencyText = entry.currency,
                        isAvailable = entry.isAvailable,
                        grantedBalanceText = entry.grantedBalance,
                        toppedUpBalanceText = entry.toppedUpBalance,
                        recordedAt = entry.timestamp,
                        message = entry.message,
                        intervalSeconds = entry.intervalSeconds.takeIf { it != 0 },
                        expectedAt = entry.expectedTime.takeIf { it != 0L },
                        alarmMethod = entry.alarmMethod.takeIf { it.isNotEmpty() },
                        missReason = entry.missReason.takeIf { it.isNotEmpty() },
                        migrationOperationId = id,
                        migrationSourceOrdinal = indexed.index,
                        legacySourceId = entry.id
                    )
                }
            )
        }
        updateOperation(id, MutationStage.ROOM_WRITTEN, snapshot.records.size.toLong())
    }

    private suspend fun publish(id: String) {
        database.withTransaction {
            val current = requireNotNull(database.mutationOperationDao().get(id))
            if (current.stage in setOf(
                    MutationStage.PUBLISHED,
                    MutationStage.ACTIVE,
                    MutationStage.CLEANED
                )
            ) {
                return@withTransaction
            }
            updateOperation(id, MutationStage.VERIFIED, current.batchCursor)
            require(database.mutationOperationDao().markPublished(id, now()) == 1)
        }
    }

    private suspend fun markCleaned(id: String) {
        val current = requireNotNull(database.mutationOperationDao().get(id))
        require(
            database.mutationOperationDao().updateStage(
                id,
                MutationStage.CLEANED,
                current.batchCursor,
                null,
                null,
                now()
            ) == 1
        )
    }

    private suspend fun failIfPresent(id: String, error: Exception) {
        val current = database.mutationOperationDao().get(id) ?: return
        require(
            database.mutationOperationDao().updateStage(
                id,
                MutationStage.FAILED,
                current.batchCursor,
                "MIGRATION_FAILED",
                error.message,
                now()
            ) == 1
        )
    }

    private suspend fun updateOperation(id: String, stage: MutationStage, cursor: Long) {
        val current = requireNotNull(database.mutationOperationDao().get(id))
        if (current.stage.ordinal < stage.ordinal || cursor > current.batchCursor) {
            require(
                database.mutationOperationDao().updateStage(
                    id,
                    stage,
                    cursor,
                    null,
                    null,
                    now()
                ) == 1
            )
        }
    }

    private suspend fun advanceMetadata(
        stage: LegacyMigrationStage,
        roomGeneration: Boolean = false
    ) {
        database.withTransaction {
            database.appMetadataDao().ensureSingleton(now())
            val current = requireNotNull(database.appMetadataDao().get())
            if (current.legacyMigrationStage.ordinal < stage.ordinal) {
                require(
                    database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
                        current.localRevision,
                        current.activeDataGeneration,
                        current.legacyMigrationStage,
                        if (roomGeneration) "ROOM" else current.activeDataGeneration,
                        stage,
                        now()
                    ) == 1
                )
            }
        }
    }

    private fun operationId(snapshot: LegacyDataSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Json.encodeToString(snapshot).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return UUID.nameUUIDFromBytes(
            ("wallet-sentinel:legacy-data:v2|$digest").toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }

    private fun summaryKey(date: String, accountId: String, currency: String) =
        "$date|$accountId|${currency.uppercase(Locale.ROOT)}"

    private fun usageId(
        usage: UsageSnapshot,
        mappings: Map<String, String>,
        operationId: String,
        sourceOrdinal: Int
    ) = UUID.nameUUIDFromBytes(
        "legacy|${requireMapping(usage.accountId, mappings)}|${usage.timestamp}|$operationId|$sourceOrdinal"
            .toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun validateMappings(snapshot: LegacyDataSnapshot, mappings: Map<String, String>) {
        (snapshot.records.map { it.accountId } +
            snapshot.summaries.map { it.accountId } +
            snapshot.usage.map { it.accountId })
            .distinct()
            .forEach { requireMapping(it, mappings) }
    }

    private fun requireMapping(legacyId: String, mappings: Map<String, String>): String =
        mappings[legacyId]?.also { UUID.fromString(it) }
            ?: error("No stable account mapping for legacy id $legacyId")

    private suspend fun recordReadFailure(error: Exception) {
        database.withTransaction {
            database.appMetadataDao().ensureSingleton(now())
            val existing = database.mutationOperationDao().get(READ_FAILURE_OPERATION)
            if (existing == null) {
                val metadata = requireNotNull(database.appMetadataDao().get())
                database.mutationOperationDao().insertPrepared(
                    MutationOperationEntity(
                        READ_FAILURE_OPERATION,
                        MutationOperationType.LEGACY_DATA_MIGRATION,
                        targetsJson = "{}",
                        baselineRevision = metadata.localRevision,
                        createdAt = now(),
                        updatedAt = now()
                    )
                )
            }
            require(
                database.mutationOperationDao().updateStage(
                    READ_FAILURE_OPERATION,
                    MutationStage.FAILED,
                    0,
                    "READ_FAILED",
                    error.message,
                    now()
                ) == 1
            )
            val current = requireNotNull(database.appMetadataDao().get())
            if (current.legacyMigrationStage != LegacyMigrationStage.FAILED) {
                require(
                    database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
                        current.localRevision,
                        current.activeDataGeneration,
                        current.legacyMigrationStage,
                        current.activeDataGeneration,
                        LegacyMigrationStage.FAILED,
                        now()
                    ) == 1
                )
            }
        }
    }

    private suspend fun resetFailedMetadata() {
        database.withTransaction {
            database.appMetadataDao().ensureSingleton(now())
            if (database.appMetadataDao().get()?.legacyMigrationStage == LegacyMigrationStage.FAILED) {
                require(database.appMetadataDao().resetFailedLegacyMigration(now()) == 1)
            }
        }
    }

    private fun LegacyDataSnapshot.isEmpty() =
        records.isEmpty() && summaries.isEmpty() && usage.isEmpty() && logs.isEmpty()
}
