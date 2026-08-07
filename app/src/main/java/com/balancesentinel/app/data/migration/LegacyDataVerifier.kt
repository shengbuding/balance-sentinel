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
        val recordCount = database.historyDao().countRecords()
        require(recordCount == mappedRecords.size.toLong()) { "Migrated record count mismatch" }
        val summaryCount = database.historyDao().countSummaries()
        require(summaryCount == snapshot.summaries.size.toLong()) { "Migrated summary count mismatch" }
        val usageCount = database.usageDao().countSnapshots()
        require(usageCount == snapshot.usage.size.toLong()) { "Migrated usage count mismatch" }
        val logCount = database.eventLogDao().countLogs()
        require(logCount == snapshot.logs.size.toLong()) { "Migrated log count mismatch" }
        mappedRecords.firstOrNull()?.let { expected ->
            val actual = database.historyDao().range(
                expected.accountId,
                expected.currency.uppercase(Locale.ROOT),
                expected.timestamp,
                expected.timestamp + 1
            ).firstOrNull()
            require(actual != null && actual.totalBalance == expected.totalBalance.toDouble()) {
                "Migrated record fields do not match source"
            }
        }
        return LegacyDataVerification(recordCount, summaryCount, usageCount, logCount)
    }

    private fun RawRecord.mapAccount(mappings: Map<String, String>) =
        mappings[accountId]?.let { copy(accountId = it) } ?: this
}
