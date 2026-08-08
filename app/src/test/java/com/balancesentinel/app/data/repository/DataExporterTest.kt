package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DataExporterTest {

    private lateinit var context: Context
    private lateinit var database: WalletDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        WalletDatabaseProvider.installForTests(database)
    }

    @After
    fun tearDown() {
        WalletDatabaseProvider.clearForTests()
        database.close()
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
        addDataExporterRoomSummaries(summary)

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
        addDataExporterRoomRecords(record)

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
        addDataExporterRoomSummaries(
            DailySummary(
                accountId = "test-acc",
                date = "2026-07-08",
                currency = "CNY",
                open = 10f,
                close = 10f,
                consumed = 0f,
                toppedUp = 0f,
                avgBalance = 10f,
                sampleCount = 1
            )
        )
        addDataExporterRoomUsage(snapshot)

        val export = DataExporter.buildExport(context)
        val parsed = json.decodeFromString<DataExport>(export)
        assertEquals(1, parsed.usageSnapshots.size)
        assertEquals("test-acc", parsed.usageSnapshots[0].accountId)
        assertEquals(1, parsed.usageSnapshots[0].records.size)
    }

    @Test
    fun `buildExport includes usage for an account without summaries`() {
        val snapshot = UsageSnapshot(
            accountId = "usage-only",
            timestamp = 1_752_009_600_000L,
            records = listOf(UsageRecord(model_name = "deepseek-chat", total_tokens = 500))
        )
        addDataExporterRoomUsage(snapshot)

        val parsed = json.decodeFromString<DataExport>(DataExporter.buildExport(context))

        assertEquals(listOf(snapshot), parsed.usageSnapshots)
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
        addDataExporterRoomLogs(entry)

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
        addDataExporterRoomSummaries(listOf(
            DailySummary(accountId = "a1", date = "2026-07-08", currency = "CNY",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f, avgBalance = 10f, sampleCount = 1)
        ))
        addDataExporterRoomRecords(listOf(
            RawRecord(accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
                totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)
        ))
        addDataExporterRoomUsage(listOf(
            UsageSnapshot(accountId = "a1", timestamp = 1752009600000L,
                records = listOf(UsageRecord(model_name = "m1", total_tokens = 100)))
        ))
        addDataExporterRoomLogs(listOf(
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
        addDataExporterRoomSummaries(listOf(
            DailySummary(accountId = "a1", date = "2026-07-08", currency = "CNY",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f, avgBalance = 10f, sampleCount = 1)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when rawRecords exist`() {
        addDataExporterRoomRecords(listOf(
            RawRecord(accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
                totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when usageSnapshots exist`() {
        addDataExporterRoomUsage(listOf(
            UsageSnapshot(accountId = "a1", timestamp = 1752009600000L)
        ))
        assertTrue(DataExporter.hasData(context))
    }

    @Test
    fun `hasData returns true when refreshLogs exist`() {
        addDataExporterRoomLogs(listOf(
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
        addDataExporterRoomSummaries(existing)

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

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(2, result.summariesInFile)
        assertEquals(1, result.summariesImported) // only the new one

        val allSummaries = readDataExporterRoomSummaries()
        assertEquals(2, allSummaries.size) // existing + 1 new
    }

    @Test
    fun `applyImport merges new records without duplicates`() {
        val existing = RawRecord(
            accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
            totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f
        )
        addDataExporterRoomRecords(existing)

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

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(2, result.recordsInFile)
        assertEquals(1, result.recordsImported)
    }

    // Mutation caught: admitting partial raw source after only a complete summary remains.
    @Test
    fun `applyImport reports and skips raw source for a summary-only key`() {
        val sourceDate = LocalDate.parse("2026-08-01")
        val zoneId = ZoneId.systemDefault()
        val retained = DailySummary(
            accountId = "a1",
            date = sourceDate.toString(),
            currency = "CNY",
            open = 12f,
            close = 9f,
            consumed = 3f,
            toppedUp = 0f,
            avgBalance = 10f,
            sampleCount = 3,
            toppedUpBalanceClose = 9f,
            generatedAt = 123L
        )
        addDataExporterRoomSummaries(retained)
        val partial = RawRecord(
            accountId = "a1",
            timestamp = sourceDate.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli(),
            currency = "cny",
            totalBalance = 4f,
            grantedBalance = 0f,
            toppedUpBalance = 4f
        )

        val result = applyDataExporterRoomImport(
            DataExport(
                exportedAt = "2026-08-04T00:00:00",
                appVersion = "1.0",
                dailySummaries = emptyList(),
                rawRecords = listOf(partial)
            )
        )

        assertEquals(1, result.recordsInFile)
        assertEquals(0, result.recordsImported)
        assertTrue(readDataExporterRoomRecords().isEmpty())
    }

    @Test
    fun `applyImport accepts late raw source while the retained source window is open`() {
        val sourceDate = LocalDate.parse("2026-08-01")
        val zoneId = ZoneId.systemDefault()
        val early = RawRecord(
            "a1",
            sourceDate.atTime(9, 0).atZone(zoneId).toInstant().toEpochMilli(),
            "CNY",
            12f,
            0f,
            12f
        )
        val late = RawRecord(
            "a1",
            sourceDate.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli(),
            "cny",
            9f,
            0f,
            9f
        )
        addDataExporterRoomSummaries(
            listOf(
                DailySummary(
                    accountId = "a1",
                    date = sourceDate.toString(),
                    currency = "CNY",
                    open = 12f,
                    close = 12f,
                    consumed = 0f,
                    toppedUp = 0f,
                    avgBalance = 12f,
                    sampleCount = 1
                )
            )
        )
        addDataExporterRoomRecords(early)

        val result = applyDataExporterRoomImport(
            DataExport(
                exportedAt = "2026-08-02T00:00:00",
                appVersion = "1.0",
                dailySummaries = emptyList(),
                rawRecords = listOf(late)
            )
        )

        assertEquals(1, result.recordsImported)
        assertEquals(listOf(early.copy(currency = "CNY"), late.copy(currency = "CNY")), readDataExporterRoomRecords().sortedBy { it.timestamp })
    }

    @Test
    fun `applyImport keeps raw records paired with newly imported same-day summaries`() {
        val date = LocalDate.parse("2026-08-05")
        val raw = RawRecord(
            accountId = "fresh-pair",
            timestamp = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            currency = "CNY",
            totalBalance = 8f,
            grantedBalance = 0f,
            toppedUpBalance = 8f
        )
        val summary = DailySummary(
            accountId = "fresh-pair",
            date = date.toString(),
            currency = "CNY",
            open = 10f,
            close = 8f,
            consumed = 2f,
            toppedUp = 0f,
            avgBalance = 9f,
            sampleCount = 2
        )

        val result = applyDataExporterRoomImport(
            DataExport(
                exportedAt = "2026-08-06T00:00:00",
                appVersion = "1.0",
                dailySummaries = listOf(summary),
                rawRecords = listOf(raw)
            )
        )

        assertEquals(1, result.summariesImported)
        assertEquals(1, result.recordsImported)
        assertEquals(listOf(raw), readDataExporterRoomRecords())
    }

    @Test
    fun `applyImport handles all-empty import`() {
        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(), rawRecords = emptyList(),
            usageSnapshots = emptyList(), refreshLogs = emptyList()
        )

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(0, result.summariesInFile)
        assertEquals(0, result.summariesImported)
        assertEquals(0, result.recordsInFile)
        assertEquals(0, result.recordsImported)
    }

    @Test
    fun `applyImport merges usageSnapshots with dedup`() {
        val existing = UsageSnapshot(accountId = "a1", timestamp = 1752009600000L)
        addDataExporterRoomUsage(existing)

        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(), rawRecords = emptyList(),
            usageSnapshots = listOf(
                existing,  // duplicate
                UsageSnapshot(accountId = "a1", timestamp = 1752096000000L)  // new
            ),
            refreshLogs = emptyList()
        )

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(2, result.snapshotsInFile)
        assertEquals(1, result.snapshotsImported)
    }

    // ═══════════════════════════════════════════════════════════
    // exportToUri — SAF URI export
    // ═══════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    @Test
    fun `exportToUri writes data to file URI`() {
        addDataExporterRoomSummaries(listOf(
            DailySummary(accountId = "a1", date = "2026-07-08", currency = "CNY",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f, avgBalance = 10f, sampleCount = 1)
        ))
        val exportFile = File(context.filesDir, "data-export-${System.nanoTime()}.json")
        val uri = Uri.fromFile(exportFile)

        val result = DataExporter.exportToUri(context, uri)
        assertTrue("export should succeed", result)
        assertTrue("export file should exist", exportFile.exists())
        val content = exportFile.readText()
        assertTrue("should contain dailySummaries", content.contains("\"dailySummaries\""))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `exportToUri produces valid JSON at file URI`() {
        addDataExporterRoomRecords(listOf(
            RawRecord(accountId = "a1", timestamp = 1752009600000L, currency = "CNY",
                totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)
        ))
        val exportFile = File(context.filesDir, "data-export2-${System.nanoTime()}.json")
        val uri = Uri.fromFile(exportFile)

        DataExporter.exportToUri(context, uri)
        val content = exportFile.readText()
        val parsed = json.decodeFromString<DataExport>(content)
        assertEquals(1, parsed.version)
        assertEquals(1, parsed.rawRecords.size)
        assertEquals("a1", parsed.rawRecords[0].accountId)
    }

    // ═══════════════════════════════════════════════════════════
    // importFromUri — SAF URI import
    // ═══════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    @Test
    fun `importFromUri parses valid data from file URI`() {
        val data = DataExport(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.2.0",
            dailySummaries = listOf(DailySummary(accountId = "imp1", date = "2026-07-08",
                currency = "CNY", open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
                avgBalance = 10f, sampleCount = 1, toppedUpBalanceClose = 10f)),
            rawRecords = emptyList(), usageSnapshots = emptyList(), refreshLogs = emptyList()
        )
        val importFile = File(context.filesDir, "import-data-${System.nanoTime()}.json")
        importFile.writeText(json.encodeToString(data))
        val uri = Uri.fromFile(importFile)

        val result = DataExporter.importFromUri(context, uri)
        assertNotNull("should parse data", result)
        assertEquals(1, result!!.dailySummaries.size)
        assertEquals("imp1", result.dailySummaries[0].accountId)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `importFromUri returns null for invalid JSON`() {
        val file = File(context.filesDir, "bad-import-${System.nanoTime()}.json")
        file.writeText("not-valid-json-at-all")
        val uri = Uri.fromFile(file)

        val result = DataExporter.importFromUri(context, uri)
        assertNull("should return null for invalid JSON", result)
    }

    @Test
    fun `importFromUri returns null for bad URI`() {
        val badUri = Uri.parse("content://nonexistent.authority/path")
        val result = DataExporter.importFromUri(context, badUri)
        assertNull("should return null for bad URI", result)
    }

    // ═══════════════════════════════════════════════════════════
    // importAndApply — convenience wrapper
    // ═══════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    @Test
    fun `importAndApply imports and merges data from file URI`() {
        val data = DataExport(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.2.0",
            dailySummaries = listOf(DailySummary(accountId = "iaa1", date = "2026-07-08",
                currency = "CNY", open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
                avgBalance = 10f, sampleCount = 1, toppedUpBalanceClose = 10f)),
            rawRecords = listOf(RawRecord(accountId = "iaa1", timestamp = 1752009600000L,
                currency = "CNY", totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)),
            usageSnapshots = emptyList(), refreshLogs = emptyList()
        )
        val file = File(context.filesDir, "import-apply-${System.nanoTime()}.json")
        file.writeText(json.encodeToString(data))
        val uri = Uri.fromFile(file)

        seedDataExporterRoomAccounts(data)
        val result = DataExporter.importAndApply(context, uri)
        assertNotNull("should return ImportResult", result)
        assertEquals(1, result!!.summariesImported)
        assertEquals(1, result.recordsImported)
    }

    @Test
    fun `importAndApply returns null when importFromUri fails`() {
        val badUri = Uri.parse("content://nonexistent.authority/path")
        val result = DataExporter.importAndApply(context, badUri)
        assertNull("should return null when import fails", result)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `applyImport merge all-new items with zero dedup`() {
        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = listOf(DailySummary(accountId = "new1", date = "2026-07-08",
                currency = "CNY", open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
                avgBalance = 10f, sampleCount = 1, toppedUpBalanceClose = 10f)),
            rawRecords = listOf(RawRecord(accountId = "new1", timestamp = 1752009600000L,
                currency = "CNY", totalBalance = 10f, grantedBalance = 0f, toppedUpBalance = 10f)),
            usageSnapshots = listOf(UsageSnapshot(accountId = "new1", timestamp = 1752009600000L)),
            refreshLogs = listOf(RefreshLogEntry(id = 999L, type = RefreshLogType.AUTO, timestamp = 1752009600000L))
        )

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(1, result.summariesImported)
        assertEquals(1, result.recordsImported)
        assertEquals(1, result.snapshotsImported)
        assertEquals(1, result.logsImported)
    }

    @Test
    fun `applyImport merge all-duplicate items imports zero`() {
        val existing = DailySummary(accountId = "dup1", date = "2026-07-08",
            currency = "CNY", open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
            avgBalance = 10f, sampleCount = 1, toppedUpBalanceClose = 10f)
        addDataExporterRoomSummaries(existing)

        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = listOf(existing),
            rawRecords = emptyList(), usageSnapshots = emptyList(), refreshLogs = emptyList()
        )

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(1, result.summariesInFile)
        assertEquals(0, result.summariesImported)
    }

    @Test
    fun `applyImport merges refreshLogs with dedup`() {
        val existing = RefreshLogEntry(id = 1L, type = RefreshLogType.AUTO, timestamp = 1752009600000L)
        addDataExporterRoomLogs(existing)

        val importedData = DataExport(
            version = 1, exportedAt = "2026-07-09T00:00:00", appVersion = "1.0",
            dailySummaries = emptyList(), rawRecords = emptyList(),
            usageSnapshots = emptyList(),
            refreshLogs = listOf(
                existing,  // duplicate
                RefreshLogEntry(id = 2L, type = RefreshLogType.MANUAL, timestamp = 1752096000000L)  // new
            )
        )

        val result = applyDataExporterRoomImport(importedData)
        assertEquals(2, result.logsInFile)
        assertEquals(1, result.logsImported)
    }

    @Test
    fun `applyImport skips unknown accounts without aborting known records`() {
        val known = RawRecord("known-account", 1_000L, "USD", 5f, 0f, 5f)
        val unknown = RawRecord("unknown-account", 2_000L, "USD", 4f, 0f, 4f)
        runBlocking { ensureDataExporterRoomAccount(known.accountId) }

        val result = applyDataExporterRoomImport(
            DataExport(
                exportedAt = "2026-08-06T00:00:00",
                appVersion = "1.0",
                dailySummaries = emptyList(),
                rawRecords = listOf(known, unknown)
            ),
            seedAccounts = false
        )

        assertEquals(2, result.recordsInFile)
        assertEquals(1, result.recordsImported)
        assertEquals(listOf(known), readDataExporterRoomRecords())
    }

    @Test
    fun `applyImport ignores duplicate logs older than the latest ten thousand`() {
        addDataExporterRoomLogs(
            (1L..10_001L).map { id ->
                RefreshLogEntry(id = id, type = RefreshLogType.AUTO, timestamp = id)
            }
        )

        val result = applyDataExporterRoomImport(
            DataExport(
                exportedAt = "2026-08-06T00:00:00",
                appVersion = "1.0",
                dailySummaries = emptyList(),
                rawRecords = emptyList(),
                refreshLogs = listOf(
                    RefreshLogEntry(id = 1L, type = RefreshLogType.AUTO, timestamp = 1L),
                    RefreshLogEntry(id = 10_002L, type = RefreshLogType.MANUAL, timestamp = 10_002L)
                )
            )
        )

        assertEquals(1, result.logsImported)
        assertEquals(10_002L, runBlocking { database.eventLogDao().countLogs() })
    }

    // Mutation caught: deduplicating raw imports by account and timestamp without currency.
    @Test
    fun `applyImport preserves same-account same-timestamp records in different currencies`() {
        val cny = RawRecord("acct", 100L, "CNY", 10f, 0f, 10f)
        val usd = RawRecord("acct", 100L, "USD", 2f, 0f, 2f)
        addDataExporterRoomRecords(cny)
        val imported = DataExport(
            exportedAt = "2026-08-03T00:00:00",
            appVersion = "1.0",
            dailySummaries = emptyList(),
            rawRecords = listOf(usd)
        )

        val result = applyDataExporterRoomImport(imported)

        assertEquals(1, result.recordsImported)
        assertEquals(listOf(cny, usd), readDataExporterRoomRecords().sortedBy { it.currency })
    }

    // Imported malformed history is skipped before it reaches Room constraints.
    @Test
    fun `applyImport skips raw records with a blank account`() {
        val incoming = RawRecord("", 100L, "USD", 2f, 0f, 2f)
        val imported = DataExport(
            exportedAt = "2026-08-03T00:00:00",
            appVersion = "1.0",
            dailySummaries = emptyList(),
            rawRecords = listOf(incoming)
        )

        val result = applyDataExporterRoomImport(imported)
        assertEquals(0, result.recordsImported)
        assertTrue(readDataExporterRoomRecords().isEmpty())
    }

    // Imported unknown summaries are skipped before they can violate Room foreign keys.
    @Test
    fun `applyImport skips summaries for an unknown account`() {
        val incoming = DailySummary(
            accountId = "unknown-account",
            date = "2026-08-01",
            currency = "USD",
            open = 2f,
            close = 2f,
            consumed = 0f,
            toppedUp = 0f,
            avgBalance = 2f,
            sampleCount = 1
        )
        val imported = DataExport(
            exportedAt = "2026-08-03T00:00:00",
            appVersion = "1.0",
            dailySummaries = listOf(incoming),
            rawRecords = emptyList()
        )

        val result = applyDataExporterRoomImport(imported, seedAccounts = false)
        assertEquals(0, result.summariesImported)
        assertTrue(readDataExporterRoomSummaries().isEmpty())
    }

    private fun addDataExporterRoomSummaries(summaries: List<DailySummary>) = runBlocking {
        summaries.map { it.accountId }.distinct().forEach { ensureDataExporterRoomAccount(it) }
        RoomHistoryRepository(database).upsertSummaries(summaries)
    }

    private fun addDataExporterRoomSummaries(vararg summaries: DailySummary) {
        addDataExporterRoomSummaries(summaries.toList())
    }

    private fun addDataExporterRoomRecords(records: List<RawRecord>) = runBlocking {
        records.map { it.accountId }.distinct().forEach { ensureDataExporterRoomAccount(it) }
        RoomHistoryRepository(database).insert(records, BalanceRecordSource.REFRESH)
    }

    private fun addDataExporterRoomRecords(vararg records: RawRecord) {
        addDataExporterRoomRecords(records.toList())
    }

    private fun addDataExporterRoomUsage(snapshots: List<UsageSnapshot>) = runBlocking {
        snapshots.map { it.accountId }.distinct().forEach { ensureDataExporterRoomAccount(it) }
        snapshots.forEachIndexed { index, snapshot ->
            RoomUsageRepository(database).upsert(
                snapshot,
                "data-exporter-fixture-$index-${snapshot.accountId}-${snapshot.timestamp}"
            )
        }
    }

    private fun addDataExporterRoomUsage(vararg snapshots: UsageSnapshot) {
        addDataExporterRoomUsage(snapshots.toList())
    }

    private fun addDataExporterRoomLogs(logs: List<RefreshLogEntry>) = runBlocking {
        RoomEventLogRepository(database).append(logs)
    }

    private fun addDataExporterRoomLogs(vararg logs: RefreshLogEntry) {
        addDataExporterRoomLogs(logs.toList())
    }

    private fun readDataExporterRoomSummaries(): List<DailySummary> = runBlocking {
        RoomHistoryRepository(database).summaries()
    }

    private fun readDataExporterRoomRecords(): List<RawRecord> = runBlocking {
        val repository = RoomHistoryRepository(database)
        val records = mutableListOf<RawRecord>()
        var cursor: HistoryCursor? = null
        while (true) {
            val page = repository.pageAll(after = cursor, limit = HistoryRepository.MAX_PAGE_SIZE)
            if (page.records.isEmpty()) break
            records += page.records.map { it.value }
            val next = page.nextCursor ?: break
            if (next == cursor) break
            cursor = next
        }
        records
    }

    private fun applyDataExporterRoomImport(
        data: DataExport,
        seedAccounts: Boolean = true
    ): DataExporter.ImportResult {
        if (seedAccounts) seedDataExporterRoomAccounts(data)
        return DataExporter.applyImport(context, data)
    }

    private fun seedDataExporterRoomAccounts(data: DataExport) {
        val ids = buildSet {
            addAll(data.dailySummaries.map { it.accountId })
            addAll(data.rawRecords.map { it.accountId })
            addAll(data.usageSnapshots.map { it.accountId })
        }
        runBlocking { ids.forEach { ensureDataExporterRoomAccount(it) } }
    }

    private suspend fun ensureDataExporterRoomAccount(accountId: String) {
        if (accountId.isBlank() || database.accountDao().get(accountId) != null) return
        database.accountDao().insertCreate(
            AccountEntity(
                id = accountId,
                displayOrder = 0,
                label = "Data exporter test account $accountId",
                providerType = ProviderType.DEEPSEEK,
                activeCredentialGeneration = "fixture",
                state = AccountState.VERIFIED,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
    }
}
