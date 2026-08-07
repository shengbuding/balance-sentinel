package com.balancesentinel.app.data.migration

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.RawRecord
import java.util.Locale

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
        mappings: Map<String, String>
    ): LegacyDataVerification {
        val mappedRecords = snapshot.records.map { it.mapAccount(mappings) }
        val recordCount = database.historyDao().countLegacyRecords()
        require(recordCount == mappedRecords.size.toLong()) { "Migrated record count mismatch" }
        mappedRecords.forEach { expected ->
            val actual = database.historyDao().range(
                expected.accountId,
                expected.currency.uppercase(Locale.ROOT),
                expected.timestamp,
                expected.timestamp + 1
            ).firstOrNull()
            require(actual != null && actual.totalBalance == expected.totalBalance.toDouble() && actual.grantedBalance == expected.grantedBalance.toDouble() && actual.toppedUpBalance == expected.toppedUpBalance.toDouble()) {
                "Migrated record fields do not match source"
            }
        }
        snapshot.summaries.forEach { expected ->
            val actual = database.historyDao().getSummary(expected.date, mappings[expected.accountId] ?: error("Missing mapping"), expected.currency.uppercase(Locale.ROOT))
            require(actual != null && actual.openBalance == expected.open.toDouble() && actual.closeBalance == expected.close.toDouble() && actual.sampleCount == expected.sampleCount) { "Migrated summary fields do not match source" }
        }
        val usageCount = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM usage_snapshots WHERE identity_discriminator = 'legacy-migration'"
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        require(usageCount == snapshot.usage.size.toLong()) { "Migrated usage count mismatch" }
        snapshot.usage.forEach { expected ->
            val id = java.util.UUID.nameUUIDFromBytes("legacy|${mappings[expected.accountId] ?: error("Missing mapping")}|${expected.timestamp}".toByteArray()).toString()
            val actual = database.usageDao().getRecords(id)
            require(actual.size == expected.records.size && actual.firstOrNull()?.totalTokens == expected.records.firstOrNull()?.total_tokens) { "Migrated usage fields do not match source" }
        }
        val logCount = database.eventLogDao().countLogs()
        require(logCount >= snapshot.logs.size) { "Migrated log count mismatch" }
        snapshot.logs.forEach { expected ->
            val actual = database.eventLogDao().get(expected.id)
            require(actual != null && actual.recordedAt == expected.timestamp && actual.message == expected.message) { "Migrated log fields do not match source" }
        }
        return LegacyDataVerification(recordCount, snapshot.summaries.size.toLong(), usageCount, snapshot.logs.size.toLong())
    }

    private fun RawRecord.mapAccount(mappings: Map<String, String>) =
        mappings[accountId]?.let { copy(accountId = it) } ?: this
}
