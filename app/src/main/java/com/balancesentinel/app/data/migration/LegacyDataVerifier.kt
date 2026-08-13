package com.balancesentinel.app.data.migration

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

@Serializable
data class LegacyMigrationManifest(
    val version: Int = 1,
    val mappings: Map<String, String> = emptyMap(),
    val baselineLegacyRecordCount: Long = 0,
    val baselineLegacyRecordMaxId: Long = 0,
    val baselineSummaryCount: Long = 0,
    val baselineUsageCount: Long = 0,
    val baselineLogCount: Long = 0,
    val expectedRecordCount: Int = 0,
    val expectedSummaryCount: Int = 0,
    val expectedUsageCount: Int = 0,
    val expectedUsageRecordCount: Int = 0,
    val expectedLogCount: Int = 0,
    val expectedSummaryKeys: List<String> = emptyList(),
    val expectedUsageIds: List<String> = emptyList(),
    val expectedLogIds: List<Long> = emptyList()
)

data class LegacyDataVerification(
    val records: Long,
    val summaries: Long,
    val usage: Long,
    val logs: Long
)

class LegacyDataVerifier(private val database: WalletDatabase) {
    companion object {
        private const val MANIFEST_VERSION = 2
        private const val VERIFY_BATCH_SIZE = 500
        private val STABLE_LEGACY_ID = Regex("(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{16})")
    }

    suspend fun verify(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String = "legacy-data",
        manifest: LegacyMigrationManifest? = null
    ): LegacyDataVerification {
        val expected = manifest ?: expectedManifest(snapshot, mappings, operationId)
        verifyManifest(snapshot, mappings, operationId, expected)
        verifyBaselines(expected)
        verifyRecords(snapshot, mappings, operationId, expected)
        verifySummaries(snapshot, mappings, operationId, expected)
        verifyUsage(snapshot, mappings, operationId, expected)
        verifyLogs(snapshot, operationId, expected)
        return LegacyDataVerification(
            records = snapshot.records.size.toLong(),
            summaries = snapshot.summaries.size.toLong(),
            usage = snapshot.usage.size.toLong(),
            logs = snapshot.logs.size.toLong()
        )
    }

    private fun expectedManifest(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String
    ) = LegacyMigrationManifest(
        version = MANIFEST_VERSION,
        mappings = mappings,
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

    private fun verifyManifest(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String,
        expected: LegacyMigrationManifest
    ) {
        require(expected.version == MANIFEST_VERSION) { "Unsupported migration manifest version" }
        require(expected.mappings == mappings) { "Migration manifest mapping mismatch" }
        require(expected.expectedRecordCount == snapshot.records.size) { "Migration manifest record count mismatch" }
        require(expected.expectedSummaryCount == snapshot.summaries.size) { "Migration manifest summary count mismatch" }
        require(expected.expectedUsageCount == snapshot.usage.size) { "Migration manifest usage count mismatch" }
        require(expected.expectedUsageRecordCount == snapshot.usage.sumOf { it.records.size }) {
            "Migration manifest usage record count mismatch"
        }
        require(expected.expectedLogCount == snapshot.logs.size) { "Migration manifest log count mismatch" }
        require(
            expected.expectedSummaryKeys == snapshot.summaries.map {
                summaryKey(it.date, requireMapping(it.accountId, mappings), it.currency)
            }
        ) { "Migration manifest summary identity mismatch" }
        require(
            expected.expectedUsageIds == snapshot.usage.mapIndexed { ordinal, usage ->
                usageId(usage, mappings, operationId, ordinal)
            }
        ) { "Migration manifest usage identity mismatch" }
        require(expected.expectedLogIds == snapshot.logs.map { it.id }) {
            "Migration manifest log identity mismatch"
        }
    }

    private suspend fun verifyBaselines(expected: LegacyMigrationManifest) {
        require(
            database.historyDao().countLegacyRecords() >=
                expected.baselineLegacyRecordCount + expected.expectedRecordCount
        ) { "Migrated record baseline mismatch" }
        require(database.historyDao().countSummaries() >= expected.baselineSummaryCount) {
            "Migrated summary baseline mismatch"
        }
        require(
            database.usageDao().countSnapshots() >=
                expected.baselineUsageCount + expected.expectedUsageCount
        ) { "Migrated usage baseline mismatch" }
        require(
            database.eventLogDao().countLogs() >=
                expected.baselineLogCount + expected.expectedLogCount
        ) { "Migrated log baseline mismatch" }
    }

    private suspend fun verifyRecords(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String,
        expected: LegacyMigrationManifest
    ) {
        require(database.historyDao().countMigrationRecords(operationId) == expected.expectedRecordCount.toLong()) {
            "Migrated record count mismatch"
        }
        var start = 0
        while (start < snapshot.records.size) {
            val end = minOf(start + VERIFY_BATCH_SIZE, snapshot.records.size)
            val actual = database.historyDao().migrationRecordPage(operationId, start, end - start)
            require(actual.size == end - start) { "Migrated record page count mismatch" }
            snapshot.records.subList(start, end).forEachIndexed { index, source ->
                val ordinal = start + index
                val row = actual[index]
                require(
                    row.migrationOperationId == operationId &&
                        row.migrationSourceOrdinal == ordinal &&
                        row.source == BalanceRecordSource.LEGACY_MIGRATION &&
                        row.accountId == requireMapping(source.accountId, mappings) &&
                        row.currency == source.currency.uppercase(Locale.ROOT) &&
                        row.recordedAt == source.timestamp &&
                        row.totalBalance == source.totalBalance.toDouble() &&
                        row.grantedBalance == source.grantedBalance.toDouble() &&
                        row.toppedUpBalance == source.toppedUpBalance.toDouble()
                ) { "Migrated record fields do not match source ordinal $ordinal" }
            }
            start = end
        }
    }

    private suspend fun verifySummaries(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String,
        expected: LegacyMigrationManifest
    ) {
        require(database.historyDao().countMigrationSummaries(operationId) == expected.expectedSummaryCount.toLong()) {
            "Migrated summary count mismatch"
        }
        var start = 0
        while (start < snapshot.summaries.size) {
            val end = minOf(start + VERIFY_BATCH_SIZE, snapshot.summaries.size)
            val actual = database.historyDao().migrationSummaryPage(operationId, start, end - start)
            require(actual.size == end - start) { "Migrated summary page count mismatch" }
            snapshot.summaries.subList(start, end).forEachIndexed { index, source ->
                val ordinal = start + index
                val row = actual[index]
                require(
                    row.migrationOperationId == operationId &&
                        row.migrationSourceOrdinal == ordinal &&
                        row.date == source.date &&
                        row.accountId == requireMapping(source.accountId, mappings) &&
                        row.currency == source.currency.uppercase(Locale.ROOT) &&
                        row.openBalance == source.open.toDouble() &&
                        row.closeBalance == source.close.toDouble() &&
                        row.consumedBalance == source.consumed.toDouble() &&
                        row.toppedUpBalance == source.toppedUp.toDouble() &&
                        row.grantedBalance == source.granted.toDouble() &&
                        row.averageBalance == source.avgBalance.toDouble() &&
                        row.sampleCount == source.sampleCount &&
                        row.toppedUpBalanceClose == source.toppedUpBalanceClose.toDouble() &&
                        row.grantedBalanceClose == source.grantedBalanceClose.toDouble() &&
                        row.generatedAt == source.generatedAt
                ) { "Migrated summary fields do not match source ordinal $ordinal" }
            }
            start = end
        }
    }

    private suspend fun verifyUsage(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String,
        expected: LegacyMigrationManifest
    ) {
        require(database.usageDao().countMigrationSnapshots(operationId) == expected.expectedUsageCount.toLong()) {
            "Migrated usage count mismatch"
        }
        var verifiedRecordCount = 0
        var start = 0
        while (start < snapshot.usage.size) {
            val end = minOf(start + VERIFY_BATCH_SIZE, snapshot.usage.size)
            val actual = database.usageDao().migrationSnapshotPage(operationId, start, end - start)
            require(actual.size == end - start) { "Migrated usage page count mismatch" }
            snapshot.usage.subList(start, end).forEachIndexed { index, source ->
                val ordinal = start + index
                val row = actual[index]
                val expectedId = usageId(source, mappings, operationId, ordinal)
                require(
                    row.id == expectedId &&
                        row.accountId == requireMapping(source.accountId, mappings) &&
                        row.capturedAt == source.timestamp &&
                        row.identityDiscriminator == "legacy-migration:$operationId:$ordinal" &&
                        row.migrationOperationId == operationId &&
                        row.migrationSourceOrdinal == ordinal
                ) { "Migrated usage snapshot fields do not match source ordinal $ordinal" }
                val records = database.usageDao().getRecords(expectedId)
                require(records.size == source.records.size) { "Migrated usage record count mismatch" }
                source.records.forEachIndexed { recordOrdinal, sourceRecord ->
                    val record = records[recordOrdinal]
                    require(
                        record.snapshotId == expectedId &&
                            record.recordOrdinal == recordOrdinal &&
                            record.modelName == sourceRecord.model_name &&
                            record.totalTokens == sourceRecord.total_tokens &&
                            record.promptTokens == sourceRecord.prompt_tokens &&
                            record.completionTokens == sourceRecord.completion_tokens
                    ) { "Migrated usage fields do not match source ordinal $ordinal" }
                }
                verifiedRecordCount += records.size
            }
            start = end
        }
        require(verifiedRecordCount == expected.expectedUsageRecordCount) {
            "Migrated usage record total mismatch"
        }
    }

    private suspend fun verifyLogs(
        snapshot: LegacyDataSnapshot,
        operationId: String,
        expected: LegacyMigrationManifest
    ) {
        require(database.eventLogDao().countMigrationLogs(operationId) == expected.expectedLogCount.toLong()) {
            "Migrated log count mismatch"
        }
        var start = 0
        while (start < snapshot.logs.size) {
            val end = minOf(start + VERIFY_BATCH_SIZE, snapshot.logs.size)
            val actual = database.eventLogDao().migrationLogPage(operationId, start, end - start)
            require(actual.size == end - start) { "Migrated log page count mismatch" }
            snapshot.logs.subList(start, end).forEachIndexed { index, source ->
                val ordinal = start + index
                val row = actual[index]
                require(
                    row.migrationOperationId == operationId &&
                        row.migrationSourceOrdinal == ordinal &&
                        row.legacySourceId == source.id &&
                        row.accountId == null &&
                        row.refreshRunId == null &&
                        row.eventType == EventLogType.valueOf(source.type.name) &&
                        row.totalBalanceText == source.totalBalance &&
                        row.currencyText == source.currency &&
                        row.isAvailable == source.isAvailable &&
                        row.grantedBalanceText == source.grantedBalance &&
                        row.toppedUpBalanceText == source.toppedUpBalance &&
                        row.recordedAt == source.timestamp &&
                        row.message == source.message &&
                        row.intervalSeconds == source.intervalSeconds.takeIf { it != 0 } &&
                        row.expectedAt == source.expectedTime.takeIf { it != 0L } &&
                        row.alarmMethod == source.alarmMethod.takeIf { it.isNotEmpty() } &&
                        row.missReason == source.missReason.takeIf { it.isNotEmpty() }
                ) { "Migrated log fields do not match source ordinal $ordinal" }
            }
            start = end
        }
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

    private fun requireMapping(legacyId: String, mappings: Map<String, String>): String =
        (mappings[legacyId] ?: mappings[canonicalLegacyId(legacyId)])
            ?.also { UUID.fromString(it) }
            ?: error("No stable account mapping for legacy id $legacyId")

    private fun canonicalLegacyId(legacyId: String): String =
        if (STABLE_LEGACY_ID.matches(legacyId)) legacyId.lowercase(Locale.ROOT) else legacyId
}
