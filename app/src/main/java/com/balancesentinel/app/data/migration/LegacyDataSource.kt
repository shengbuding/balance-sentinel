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
import kotlinx.serialization.Serializable

/** Snapshot of all legacy JSON-backed data read before any writes occur. */
@Serializable
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
    } catch (failure: Exception) {
        val restored = listOf(
            runCatching { RawRecordStore.restoreRecords(appContext, snapshot.records).let { true } }.getOrDefault(false),
            runCatching { DailySummaryStore.restoreSummaries(appContext, snapshot.summaries).let { true } }.getOrDefault(false),
            runCatching { UsageDataStore.restoreAll(appContext, snapshot.usage).let { true } }.getOrDefault(false),
            runCatching { RefreshLogStore.restoreEntries(appContext, snapshot.logs).let { true } }.getOrDefault(false)
        ).all { it }
        if (!restored) throw IllegalStateException("Legacy cleanup preimage restore failed", failure)
        false
    }
}

/** Compatibility name used by callers that want the production adapter. */
typealias SharedPreferencesLegacyDataSource = LegacyStoresDataSource
