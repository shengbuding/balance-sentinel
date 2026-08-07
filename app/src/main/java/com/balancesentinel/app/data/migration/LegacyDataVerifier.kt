package com.balancesentinel.app.data.migration

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class LegacyMigrationManifest(
    val mappings: Map<String, String> = emptyMap(),
    val baselineLegacyRecordCount: Long = 0,
    val baselineLegacyRecordMaxId: Long = 0,
    val expectedRecordCount: Int = 0,
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

/** Verifies migrated row counts and representative fields before publication. */
class LegacyDataVerifier(private val database: WalletDatabase) {
    suspend fun verify(
        snapshot: LegacyDataSnapshot,
        mappings: Map<String, String>,
        operationId: String = "legacy-data",
        manifest: LegacyMigrationManifest? = null
    ): LegacyDataVerification {
        val expected = manifest ?: LegacyMigrationManifest(
            mappings = mappings,
            expectedRecordCount = snapshot.records.size,
            expectedSummaryKeys = snapshot.summaries.map { summaryKey(it.date, mappings[it.accountId] ?: error("Missing mapping"), it.currency) },
            expectedUsageIds = snapshot.usage.map { usageId(it.accountId, it.timestamp, mappings, operationId) },
            expectedLogIds = snapshot.logs.map { it.id }
        )
        val mappedRecords = snapshot.records.map { it.mapAccount(mappings) }
        require(expected.expectedRecordCount == mappedRecords.size) { "Migration manifest record count mismatch" }
        val currentLegacyCount = database.historyDao().countLegacyRecords()
        require(currentLegacyCount - expected.baselineLegacyRecordCount == mappedRecords.size.toLong()) { "Migrated record count mismatch" }
        val seenByKey = mutableMapOf<Triple<String, Long, String>, Int>()
        mappedRecords.forEach { sourceRecord ->
            val key = Triple(sourceRecord.accountId, sourceRecord.timestamp, sourceRecord.currency.uppercase(Locale.ROOT))
            val occurrence = seenByKey.getOrDefault(key, 0)
            seenByKey[key] = occurrence + 1
            val candidates = database.historyDao().legacyRecordsAt(key.first, key.third, key.second, expected.baselineLegacyRecordMaxId)
            val actual = candidates.getOrNull(occurrence)
            require(actual != null && actual.totalBalance == sourceRecord.totalBalance.toDouble() && actual.grantedBalance == sourceRecord.grantedBalance.toDouble() && actual.toppedUpBalance == sourceRecord.toppedUpBalance.toDouble()) {
                "Migrated record fields do not match source"
            }
        }
        val summaryKeys = snapshot.summaries.map { summaryKey(it.date, mappings[it.accountId] ?: error("Missing mapping"), it.currency) }
        require(summaryKeys.distinct().size == expected.expectedSummaryKeys.distinct().size && summaryKeys.toSet() == expected.expectedSummaryKeys.toSet()) { "Migrated summary count mismatch" }
        snapshot.summaries.forEach { sourceSummary ->
            val actual = database.historyDao().getSummary(sourceSummary.date, mappings[sourceSummary.accountId] ?: error("Missing mapping"), sourceSummary.currency.uppercase(Locale.ROOT))
            require(actual != null && actual.openBalance == sourceSummary.open.toDouble() && actual.closeBalance == sourceSummary.close.toDouble() && actual.sampleCount == sourceSummary.sampleCount && actual.consumedBalance == sourceSummary.consumed.toDouble() && actual.toppedUpBalance == sourceSummary.toppedUp.toDouble() && actual.grantedBalance == sourceSummary.granted.toDouble()) { "Migrated summary fields do not match source" }
        }
        val usageIds = snapshot.usage.map { usageId(it.accountId, it.timestamp, mappings, operationId) }
        require(usageIds.toSet() == expected.expectedUsageIds.toSet()) { "Migrated usage count mismatch" }
        val usageCount = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM usage_snapshots WHERE identity_discriminator = 'legacy-migration:$operationId'").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        require(usageCount == expected.expectedUsageIds.distinct().size.toLong()) { "Migrated usage count mismatch" }
        snapshot.usage.forEach { sourceUsage ->
            val id = usageId(sourceUsage.accountId, sourceUsage.timestamp, mappings, operationId)
            val actual = database.usageDao().getRecords(id)
            require(actual.size == sourceUsage.records.size && actual.map { it.modelName } == sourceUsage.records.map { it.model_name } && actual.sumOf { it.totalTokens } == sourceUsage.records.sumOf { it.total_tokens }) { "Migrated usage fields do not match source" }
        }
        require(snapshot.logs.map { it.id }.toSet() == expected.expectedLogIds.toSet()) { "Migrated log count mismatch" }
        snapshot.logs.forEach { sourceLog ->
            val actual = database.eventLogDao().get(sourceLog.id)
            require(actual != null && actual.recordedAt == sourceLog.timestamp && actual.message == sourceLog.message && actual.currencyText == sourceLog.currency && actual.totalBalanceText == sourceLog.totalBalance) { "Migrated log fields do not match source" }
        }
        return LegacyDataVerification(mappedRecords.size.toLong(), snapshot.summaries.size.toLong(), usageCount, snapshot.logs.size.toLong())
    }

    private fun RawRecord.mapAccount(mappings: Map<String, String>) =
        mappings[accountId]?.let { copy(accountId = it) } ?: this

    private fun summaryKey(date: String, accountId: String, currency: String) = "$date|$accountId|${currency.uppercase(Locale.ROOT)}"
    private fun usageId(legacyAccountId: String, timestamp: Long, mappings: Map<String, String>, operationId: String) =
        java.util.UUID.nameUUIDFromBytes("legacy|${mappings[legacyAccountId] ?: error("Missing mapping")}|$timestamp|$operationId".toByteArray()).toString()
}
