package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataExporterTest {

    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Clean up all stores after each test
        clearAllStores()
    }

    private fun clearAllStores() {
        // Clear by writing empty lists to each store
        val prefs = context.getSharedPreferences("daily_summaries", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val rawPrefs = context.getSharedPreferences("raw_records", Context.MODE_PRIVATE)
        rawPrefs.edit().clear().commit()
        val usagePrefs = context.getSharedPreferences("usage_snapshots", Context.MODE_PRIVATE)
        usagePrefs.edit().clear().commit()
        val logPrefs = context.getSharedPreferences("refresh_log_store", Context.MODE_PRIVATE)
        logPrefs.edit().clear().commit()
    }

    // ═══════════════════════════════════════════════════════════
    // buildExport
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildExport produces valid JSON with version and exportedAt`() {
        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.version)
        assertTrue(parsed.exportedAt.isNotEmpty())
        assertTrue(parsed.appVersion.isNotEmpty())
    }

    @Test
    fun `buildExport includes dailySummaries`() {
        val summary = DailySummary(
            accountId = "test-acc",
            date = "2026-07-08",
            currency = "CNY",
            open = 10.0f,
            close = 8.0f,
            consumed = 2.0f,
            toppedUp = 0f,
            avgBalance = 9.0f,
            sampleCount = 5,
            toppedUpBalanceClose = 8.0f
        )
        DailySummaryStore.addSummaries(context, listOf(summary))

        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.dailySummaries.size)
        assertEquals("test-acc", parsed.dailySummaries[0].accountId)
        assertEquals("2026-07-08", parsed.dailySummaries[0].date)
        assertEquals(10.0f, parsed.dailySummaries[0].open)
    }

    @Test
    fun `buildExport includes rawRecords`() {
        val record = RawRecord(
            accountId = "test-acc",
            timestamp = 1752009600000L,
            currency = "CNY",
            totalBalance = 10.5f,
            grantedBalance = 0f,
            toppedUpBalance = 10.5f
        )
        RawRecordStore.addRecords(context, listOf(record))

        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.rawRecords.size)
        assertEquals("test-acc", parsed.rawRecords[0].accountId)
        assertEquals(10.5f, parsed.rawRecords[0].totalBalance)
    }

    @Test
    fun `buildExport includes usageSnapshots`() {
        val snapshot = UsageSnapshot(
            accountId = "test-acc",
            timestamp = 1752009600000L,
            records = listOf(UsageRecord(model_name = "deepseek-chat", total_tokens = 500))
        )
        UsageDataStore.saveSnapshots(context, listOf(snapshot))

        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.usageSnapshots.size)
        assertEquals("test-acc", parsed.usageSnapshots[0].accountId)
        assertEquals(1, parsed.usageSnapshots[0].records.size)
    }

    @Test
    fun `buildExport includes refreshLogs`() {
        val entry = RefreshLogEntry(
            id = 1L,
            type = RefreshLogType.MANUAL,
            totalBalance = "10.50",
            currency = "CNY",
            isAvailable = true,
            timestamp = 1752009600000L,
            message = "test refresh"
        )
        RefreshLogStore.addEntries(context, listOf(entry))

        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.refreshLogs.size)
        assertEquals(1L, parsed.refreshLogs[0].id)
        assertEquals(RefreshLogType.MANUAL, parsed.refreshLogs[0].type)
        assertEquals("10.50", parsed.refreshLogs[0].totalBalance)
    }

    @Test
    fun `buildExport handles empty stores gracefully`() {
        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertTrue(parsed.dailySummaries.isEmpty())
        assertTrue(parsed.rawRecords.isEmpty())
        assertTrue(parsed.usageSnapshots.isEmpty())
        assertTrue(parsed.refreshLogs.isEmpty())
    }

    @Test
    fun `buildExport includes all data types simultaneously`() {
        DailySummaryStore.addSummaries(context, listOf(
            DailySummary(accountId = "a1", date = "2026-07-08", currency = "CNY",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f, avgBalance = 10f, sampleCount = 1)
        ))
        RawRecordStore.addRecords(context, listOf(
            RawRecord(accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
                totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)
        ))
        UsageDataStore.saveSnapshots(context, listOf(
            UsageSnapshot(accountId = "a1", timestamp = 1752009600000L,
                records = listOf(UsageRecord(model_name = "m1", total_tokens = 100)))
        ))
        RefreshLogStore.addEntries(context, listOf(
            RefreshLogEntry(id = 1L, type = RefreshLogType.AUTO, timestamp = 1752009600000L)
        ))

        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.dailySummaries.size)
        assertEquals(1, parsed.rawRecords.size)
        assertEquals(1, parsed.usageSnapshots.size)
        assertEquals(1, parsed.refreshLogs.size)
    }

    // ═══════════════════════════════════════════════════════════
    // hasData
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `hasData returns false when all stores empty`() {
        assertFalse(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when dailySummaries exist`() {
        DailySummaryStore.addSummaries(context, listOf(
            DailySummary(accountId = "a1", date = "2026-07-08", currency = "CNY",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f, avgBalance = 10f, sampleCount = 1)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when rawRecords exist`() {
        RawRecordStore.addRecords(context, listOf(
            RawRecord(accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
                totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when usageSnapshots exist`() {
        UsageDataStore.saveSnapshots(context, listOf(
            UsageSnapshot(accountId = "a1", timestamp = 1752009600000L)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when refreshLogs exist`() {
        RefreshLogStore.addEntries(context, listOf(
            RefreshLogEntry(id = 1L, type = RefreshLogType.AUTO, timestamp = 1752009600000L)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    // ═══════════════════════════════════════════════════════════
    // ImportResult
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ImportResult fields are accessible`() {
        val result = DataExporter.ImportResult(
            summariesInFile = 5, summariesImported = 3,
            recordsInFile = 10, recordsImported = 7,
            snapshotsInFile = 2, snapshotsImported = 1,
            logsInFile = 8, logsImported = 4
        )
        assertEquals(5, result.summariesInFile)
        assertEquals(3, result.summariesImported)
        assertEquals(10, result.recordsInFile)
        assertEquals(7, result.recordsImported)
        assertEquals(2, result.snapshotsInFile)
        assertEquals(1, result.snapshotsImported)
        assertEquals(8, result.logsInFile)
        assertEquals(4, result.logsImported)
    }

    @Test
    fun `ImportResult with zero imported works`() {
        val result = DataExporter.ImportResult(
            summariesInFile = 5, summariesImported = 0,
            recordsInFile = 0, recordsImported = 0,
            snapshotsInFile = 0, snapshotsImported = 0,
            logsInFile = 0, logsImported = 0
        )
        assertEquals(5, result.summariesInFile)
        assertEquals(0, result.summariesImported)
    }

    // ═══════════════════════════════════════════════════════════
    // applyImport — merge with dedup
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `applyImport merges new summaries without duplicates`() {
        // Pre-populate one existing summary
        val existing = DailySummary(
            accountId = "a1", date = "2026-07-08", currency = "CNY",
            open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
            avgBalance = 10f, sampleCount = 1
        )
        DailySummaryStore.addSummaries(context, listOf(existing))

        // Import: one duplicate, one new
        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = listOf(
                existing,  // duplicate — should be skipped
                DailySummary(accountId = "a1", date = "2026-07-09", currency = "CNY",
                    open = 12f, close = 12f, consumed = 0f, toppedUp = 0f,
                    avgBalance = 12f, sampleCount = 1)  // new
            ),
            rawRecords = emptyList(),
            usageSnapshots = emptyList(),
            refreshLogs = emptyList()
        )

        val result = DataExporter.applyImport(context, importedData)
        assertEquals(2, result.summariesInFile)
        assertEquals(1, result.summariesImported) // only the new one

        val allSummaries = DailySummaryStore.getSummaries(context)
        assertEquals(2, allSummaries.size) // existing + 1 new
    }

    @Test
    fun `applyImport merges new records without duplicates`() {
        val existing = RawRecord(
            accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
            totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f
        )
        RawRecordStore.addRecords(context, listOf(existing))

        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(),
            rawRecords = listOf(
                existing,  // duplicate
                RawRecord(accountId = "a1", timestamp = 1752096000000L, currency = "CNY",
                    totalBalance = 12f, grantedBalance = 0f, toppedUpBalance = 12f)  // new
            ),
            usageSnapshots = emptyList(),
            refreshLogs = emptyList()
        )

        val result = DataExporter.applyImport(context, importedData)
        assertEquals(2, result.recordsInFile)
        assertEquals(1, result.recordsImported)
    }

    @Test
    fun `applyImport handles all-empty import`() {
        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(), rawRecords = emptyList(),
            usageSnapshots = emptyList(), refreshLogs = emptyList()
        )

        val result = DataExporter.applyImport(context, importedData)
        assertEquals(0, result.summariesInFile)
        assertEquals(0, result.summariesImported)
        assertEquals(0, result.recordsInFile)
        assertEquals(0, result.recordsImported)
    }

    @Test
    fun `applyImport merges usageSnapshots with dedup`() {
        val existing = UsageSnapshot(accountId = "a1", timestamp = 1752009600000L)
        UsageDataStore.saveSnapshots(context, listOf(existing))

        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(), rawRecords = emptyList(),
            usageSnapshots = listOf(
                existing,  // duplicate
                UsageSnapshot(accountId = "a1", timestamp = 1752096000000L)  // new
            ),
            refreshLogs = emptyList()
        )

        val result = DataExporter.applyImport(context, importedData)
        assertEquals(2, result.snapshotsInFile)
        assertEquals(1, result.snapshotsImported)
    }

    @Test
    fun `applyImport merges refreshLogs with dedup`() {
        val existing = RefreshLogEntry(id = 1L, type = RefreshLogType.AUTO, timestamp = 1752009600000L)
        RefreshLogStore.addEntries(context, listOf(existing))

        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(), rawRecords = emptyList(),
            usageSnapshots = emptyList(),
            refreshLogs = listOf(
                existing,  // duplicate
                RefreshLogEntry(id = 2L, type = RefreshLogType.MANUAL, timestamp = 1752096000000L)  // new
            )
        )

        val result = DataExporter.applyImport(context, importedData)
        assertEquals(2, result.logsInFile)
        assertEquals(1, result.logsImported)
    }
}
