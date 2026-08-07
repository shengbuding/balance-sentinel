package com.balancesentinel.app.data.migration

import android.content.Context
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.UsageDataStore

/** Snapshot of all legacy JSON-backed data read before any writes occur. */
data class LegacyDataSnapshot(
    val records: List<RawRecord> = emptyList(),
    val summaries: List<DailySummary> = emptyList(),
    val usage: List<UsageSnapshot> = emptyList(),
    val logs: List<RefreshLogEntry> = emptyList()
)

/** Injectable seam for migration tests and alternate legacy storage implementations. */
interface LegacyDataSource {
    fun read(): LegacyDataSnapshot
    fun clear(snapshot: LegacyDataSnapshot): Boolean = clear()
    fun clear(): Boolean = true
}

/** Production adapter delegating reads and cleanup to the existing legacy stores. */
class LegacyStoresDataSource(context: Context) : LegacyDataSource {
    private val appContext = context.applicationContext

    override fun read(): LegacyDataSnapshot = LegacyDataSnapshot(
        records = RawRecordStore.getAllRecordsStrict(appContext),
        summaries = DailySummaryStore.getSummariesStrict(appContext),
        usage = UsageDataStore.getAllSnapshotsStrict(appContext),
        logs = RefreshLogStore.getEntriesStrict(appContext)
    )

    override fun clear(snapshot: LegacyDataSnapshot): Boolean = try {
        RawRecordStore.clear(appContext)
        DailySummaryStore.clear(appContext)
        UsageDataStore.clear(appContext)
        RefreshLogStore.clearStrict(appContext)
        true
    } catch (_: Exception) {
        runCatching { RawRecordStore.restoreRecords(appContext, snapshot.records) }
        runCatching { DailySummaryStore.restoreSummaries(appContext, snapshot.summaries) }
        runCatching { UsageDataStore.restoreAll(appContext, snapshot.usage) }
        runCatching { RefreshLogStore.restoreEntries(appContext, snapshot.logs) }
        false
    }
}

/** Compatibility name used by callers that want the production adapter. */
typealias SharedPreferencesLegacyDataSource = LegacyStoresDataSource
