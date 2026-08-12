package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.io.HistoryExportHeader
import com.balancesentinel.app.data.io.HistoryExportSource
import com.balancesentinel.app.data.io.HistoryUriStorage
import com.balancesentinel.app.data.io.HistoryJsonConsumer
import com.balancesentinel.app.data.io.HistoryJsonLimits
import com.balancesentinel.app.data.io.HistoryJsonReader
import com.balancesentinel.app.data.io.HistoryJsonWriter
import com.balancesentinel.app.data.io.HistoryLogPage
import com.balancesentinel.app.data.io.HistoryLogCursor
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.api.ProviderType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryStreamingLargeDatasetTest {
    private lateinit var context: Context
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        WalletDatabaseProvider.installForTests(database)
        runBlocking { ensureAccount("account") }
    }

    @After
    fun tearDown() {
        WalletDatabaseProvider.clearForTests()
    }

    @Test
    fun `ninety thousand records export in pages and re-import in fixed chunks`() = runBlocking {
        repeat(180) { page ->
            RoomHistoryRepository(database).insert(
                List(500) { item ->
                    val ordinal = page * 500 + item
                    RawRecord("account", ordinal.toLong(), "USD", 1f, 0f, 1f)
                },
                BalanceRecordSource.REFRESH
            )
        }
        val requested = mutableListOf<Int>()
        val source = DataExporter.roomHistoryExportSource(database) { section, limit ->
            if (section == "rawRecords") requested += limit
        }
        val file = File(context.filesDir, "room-90k-${System.nanoTime()}.json")

        assertTrue(DataExporter.exportToUri(context, Uri.fromFile(file), source = source))
        database.historyDao().clearRecords()
        val result = DataExporter.importAndApply(context, Uri.fromFile(file), database = database)

        assertEquals(90_000, result?.recordsInFile)
        assertEquals(90_000, result?.recordsImported)
        assertEquals(90_000L, database.historyDao().countRecords())
        assertTrue(requested.isNotEmpty())
        assertTrue(requested.all { it == 500 })
    }

    @Test
    fun `room export snapshots before paging and validates after paging`() = runBlocking {
        val transactionStates = mutableListOf<Boolean>()
        val source = DataExporter.roomHistoryExportSource(database) { _, _ ->
            transactionStates += database.inTransaction()
        }
        val storage = MemoryUriStorage(byteArrayOf(), atomicReplace = true)

        assertTrue(DataExporter.exportToUri(context, TEST_URI, source = source, storage = storage))
        assertTrue(transactionStates.isNotEmpty())
        assertTrue(transactionStates.none { it })
    }

    @Test
    fun `room export retries same-count usage content changes without holding a write-blocking transaction`() = runTest {
        val persistence = RoomRefreshPersistence(database)
        val initial = UsageSnapshot(
            accountId = "account",
            timestamp = 1L,
            records = listOf(UsageRecord("old-model", 10L))
        )
        persistence.commit(emptyList(), listOf(initial), emptyList(), identityDiscriminator = "refresh")

        val delegate = DataExporter.roomHistoryExportSource(database)
        val usagePageEntered = CompletableDeferred<Unit>()
        val releaseUsagePage = CompletableDeferred<Unit>()
        var pauseOnce = true
        val source = object : HistoryExportSource {
            override suspend fun <T> withConsistentSnapshot(block: suspend () -> T): T =
                delegate.withConsistentSnapshot(block)

            override suspend fun dailySummaryPage(offset: Int, limit: Int) =
                delegate.dailySummaryPage(offset, limit)

            override suspend fun rawRecordPage(after: HistoryCursor?, limit: Int) =
                delegate.rawRecordPage(after, limit)

            override suspend fun usageSnapshotPage(offset: Int, limit: Int): List<UsageSnapshot> {
                val page = delegate.usageSnapshotPage(offset, limit)
                if (pauseOnce && offset == 0 && page.isNotEmpty()) {
                    pauseOnce = false
                    usagePageEntered.complete(Unit)
                    releaseUsagePage.await()
                }
                return page
            }

            override suspend fun refreshLogPage(after: HistoryLogCursor?, limit: Int) =
                delegate.refreshLogPage(after, limit)
        }
        val storage = MemoryUriStorage(byteArrayOf(), atomicReplace = true)
        val export = async {
            DataExporter.exportToUri(context, TEST_URI, source = source, storage = storage)
        }

        usagePageEntered.await()
        val updated = initial.copy(records = listOf(UsageRecord("new-model", 99L)))
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                persistence.commit(emptyList(), listOf(updated), emptyList(), identityDiscriminator = "refresh")
            }
        }
        releaseUsagePage.complete(Unit)

        assertTrue(export.await())
        assertTrue(storage.text().contains("new-model"))
        assertFalse(storage.text().contains("old-model"))
        val snapshotIds = database.usageDao().exportPage(0, 10).map { it.id }
        assertEquals(1L, database.usageDao().countRecordsForSnapshots(snapshotIds))
    }

    @Test
    fun `required top level sections reject missing duplicate and unsupported schema`() = runBlocking {
        val invalid = listOf(
            "{}",
            completeJson(version = 2),
            completeJson(rawRecords = "[]", duplicateRawRecords = "[]")
        )
        invalid.forEach { json ->
            val callbacks = mutableListOf<String>()
            val failure = runCatching {
                HistoryJsonReader().read(ByteArrayInputStream(json.toByteArray()), object : EmptyConsumer() {
                    override suspend fun rawRecords(items: List<RawRecord>): Int {
                        callbacks += "raw"
                        return items.size
                    }
                })
            }.exceptionOrNull()
            assertTrue("invalid schema must be rejected: $json", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `duplicate raw arrays cannot reset count limit or publish either array`() = runBlocking {
        val one = rawRecordJson(1)
        val duplicate = completeJson(rawRecords = "[$one,$one]", duplicateRawRecords = "[$one]")
        val file = File(context.filesDir, "duplicate-raw-${System.nanoTime()}.json").apply {
            writeText(duplicate)
        }
        val result = DataExporter.importAndApply(
            context,
            Uri.fromFile(file),
            database = database,
            limits = smallLimits(maxRawRecords = 2)
        )

        assertNull(result)
        assertEquals(0L, database.historyDao().countRecords())
    }

    @Test
    fun `caller cancellation stops export and leaves destination unchanged`() = runTest {
        val enteredSecondPage = CompletableDeferred<Unit>()
        val source = object : GeneratedRawSource(1) {
            override suspend fun rawRecordPage(after: HistoryCursor?, limit: Int): HistoryPage {
                if (after != null) {
                    enteredSecondPage.complete(Unit)
                    awaitCancellation()
                }
                return super.rawRecordPage(after, limit)
            }
        }
        val storage = MemoryUriStorage("previous".toByteArray())
        val operation = async {
            DataExporter.exportToUri(context, TEST_URI, source = source, storage = storage)
        }
        enteredSecondPage.await()
        operation.cancel()
        try {
            operation.await()
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            Unit
        }

        assertEquals("previous", storage.text())
        assertEquals(0, storage.outputOpenCount)
    }

    @Test
    fun `caller cancellation rolls back imported chunks`() = runTest {
        val json = completeJson(rawRecords = "[${rawRecordJson(1)},${rawRecordJson(2)}]")
        val storage = MemoryUriStorage(json.toByteArray())
        val firstChunkWritten = CompletableDeferred<Unit>()
        val delegate = DataExporter.roomHistoryImportConsumer(database)
        val consumer = object : EmptyConsumer() {
            override suspend fun rawRecords(items: List<RawRecord>): Int {
                val written = delegate.rawRecords(items)
                firstChunkWritten.complete(Unit)
                awaitCancellation()
            }
        }
        val operation = async {
            DataExporter.importAndApply(
                context,
                TEST_URI,
                database = database,
                storage = storage,
                limits = smallLimits(maxRawRecords = 2).copy(chunkSize = 1),
                consumer = consumer
            )
        }
        firstChunkWritten.await()
        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertEquals(0L, database.historyDao().countRecords())
    }

    @Test
    fun `opaque existing destination is rejected before destructive publication`() = runBlocking {
        val storage = MemoryUriStorage("previous-json".toByteArray())
        val result = DataExporter.exportToUri(
            context,
            TEST_URI,
            source = GeneratedRawSource(2),
            storage = storage
        )

        assertFalse(result)
        assertEquals("previous-json", storage.text())
        assertEquals(0, storage.outputOpenCount)
    }

    @Test
    fun `persistent publication failure cannot corrupt an existing destination`() = runBlocking {
        val storage = MemoryUriStorage(
            "previous-json".toByteArray(),
            failEveryOutputAfter = 12
        )

        assertFalse(DataExporter.exportToUri(context, TEST_URI, GeneratedRawSource(2), storage))
        assertEquals("previous-json", storage.text())
        assertEquals(0, storage.outputOpenCount)
    }

    @Test
    fun `failed new destination publication never reports success and cleans up`() = runBlocking {
        val storage = MemoryUriStorage(byteArrayOf(), failEveryOutputAfter = 12)

        assertFalse(DataExporter.exportToUri(context, TEST_URI, GeneratedRawSource(2), storage))
        assertEquals("", storage.text())
        assertEquals(1, storage.deleteCount)
    }

    @Test
    fun `writer boundaries publish only complete output and plus one preserves target`() = runBlocking {
        val limits = smallLimits(maxRawRecords = 2)
        val boundary = MemoryUriStorage("old".toByteArray(), atomicReplace = true)
        assertTrue(DataExporter.exportToUri(context, TEST_URI, GeneratedRawSource(2), boundary, limits))
        assertNotEquals("old", boundary.text())

        val plusOne = MemoryUriStorage("old".toByteArray())
        assertFalse(DataExporter.exportToUri(context, TEST_URI, GeneratedRawSource(3), plusOne, limits))
        assertEquals("old", plusOne.text())
        assertEquals(0, plusOne.outputOpenCount)
    }

    @Test
    fun `usage records are bounded on reader and writer`() = runBlocking {
        val limits = smallLimits().copy(maxUsageRecordsPerSnapshot = 2)
        val boundaryRecords = List(2) { UsageRecord("model-$it", it.toLong()) }
        val plusOneRecords = List(3) { UsageRecord("model-$it", it.toLong()) }
        assertEquals(
            1,
            HistoryJsonReader(limits).read(
                ByteArrayInputStream(completeJson(usageSnapshots = usageJson(boundaryRecords)).toByteArray()),
                EmptyConsumer()
            ).snapshotsInFile
        )
        assertTrue(
            runCatching {
                HistoryJsonReader(limits).read(
                    ByteArrayInputStream(completeJson(usageSnapshots = usageJson(plusOneRecords)).toByteArray()),
                    EmptyConsumer()
                )
            }.exceptionOrNull() is IllegalArgumentException
        )

        val storage = MemoryUriStorage("old".toByteArray())
        val source = UsageSource(UsageSnapshot("account", 1L, plusOneRecords))
        assertFalse(DataExporter.exportToUri(context, TEST_URI, source, storage, limits))
        assertEquals("old", storage.text())
    }

    @Test
    fun `all fixed production limits retain their documented values`() {
        assertEquals(256L * 1024L * 1024L, HistoryJsonLimits.MAX_FILE_BYTES)
        assertEquals(100_000, HistoryJsonLimits.MAX_RAW_RECORDS)
        assertEquals(50_000, HistoryJsonLimits.MAX_SUMMARIES)
        assertEquals(10_000, HistoryJsonLimits.MAX_USAGE_SNAPSHOTS)
        assertEquals(10_000, HistoryJsonLimits.MAX_REFRESH_LOGS)
        assertEquals(256 * 1024, HistoryJsonLimits.MAX_FIELD_CHARS)
        assertEquals(32, HistoryJsonLimits.MAX_JSON_DEPTH)
        assertEquals(500, HistoryJsonLimits.PAGE_SIZE)
        assertEquals(500, HistoryJsonLimits.CHUNK_SIZE)
    }

    @Test
    fun `every count limit accepts boundary and rejects plus one before publication`() = runBlocking {
        val limits = HistoryJsonLimits(
            maxFileBytes = 4L * 1024 * 1024,
            maxRawRecords = 2,
            maxSummaries = 2,
            maxUsageSnapshots = 2,
            maxRefreshLogs = 2,
            maxFieldChars = 1024,
            maxJsonDepth = 32,
            pageSize = 500,
            chunkSize = 2
        )
        val arrays = listOf("dailySummaries", "rawRecords", "usageSnapshots", "refreshLogs")
        arrays.forEach { arrayName ->
            val boundary = historyJson(arrayName, 2)
            val plusOne = historyJson(arrayName, 3)
            assertEquals(2, readPublishedCount(boundary, limits))
            assertRejectedWithoutDurablePublication(plusOne, limits)
        }
    }

    @Test
    fun `file field and depth limits accept boundary and reject plus one before publication`() = runBlocking {
        val base = HistoryJsonLimits(
            maxFileBytes = 512,
            maxRawRecords = 2,
            maxSummaries = 2,
            maxUsageSnapshots = 2,
            maxRefreshLogs = 2,
            maxFieldChars = 32,
            maxJsonDepth = 4,
            pageSize = 500,
            chunkSize = 2
        )

        assertEquals(1, readPublishedCount(historyJson("refreshLogs", 1, "x".repeat(32)), base))
        assertRejectedWithoutDurablePublication(historyJson("refreshLogs", 1, "x".repeat(33)), base)

        val depthBoundary = historyJsonWithUnknownDepth(4)
        assertEquals(0, readPublishedCount(depthBoundary, base))
        assertRejectedWithoutDurablePublication(historyJsonWithUnknownDepth(5), base)

        val exactBytes = historyJson("rawRecords", 1).toByteArray().size.toLong()
        assertEquals(1, readPublishedCount(historyJson("rawRecords", 1), base.copy(maxFileBytes = exactBytes)))
        assertRejectedWithoutDurablePublication(
            historyJson("rawRecords", 1),
            base.copy(maxFileBytes = exactBytes - 1)
        )
    }

    private suspend fun readPublishedCount(json: String, limits: HistoryJsonLimits): Int {
        var published = 0
        val staged = mutableListOf<Any>()
        HistoryJsonReader(limits).read(ByteArrayInputStream(json.toByteArray()), object : EmptyConsumer() {
            override suspend fun dailySummaries(items: List<DailySummary>) = stage(items, staged)
            override suspend fun rawRecords(items: List<RawRecord>) = stage(items, staged)
            override suspend fun usageSnapshots(items: List<UsageSnapshot>) = stage(items, staged)
            override suspend fun refreshLogs(items: List<RefreshLogEntry>) = stage(items, staged)
        })
        published = staged.size
        return published
    }

    private suspend fun assertRejectedWithoutDurablePublication(json: String, limits: HistoryJsonLimits) {
        val file = File(context.filesDir, "rejected-${System.nanoTime()}.json").apply { writeText(json) }
        val result = DataExporter.importAndApply(
            context,
            Uri.fromFile(file),
            database = database,
            limits = limits
        )
        assertNull("expected bounded parser rejection", result)
        assertEquals(0L, database.historyDao().countSummaries())
        assertEquals(0L, database.historyDao().countRecords())
        assertEquals(0L, database.usageDao().countSnapshots())
        assertEquals(0L, database.eventLogDao().countLogs())
    }

    private fun stage(items: List<*>, target: MutableList<Any>): Int {
        target.addAll(items.filterNotNull())
        return items.size
    }

    private fun historyJson(arrayName: String, count: Int, message: String = ""): String {
        val item = when (arrayName) {
            "dailySummaries" -> """{"accountId":"account","date":"2026-08-09","currency":"USD","open":1,"close":1,"consumed":0,"toppedUp":0,"avgBalance":1,"sampleCount":1}"""
            "rawRecords" -> """{"accountId":"account","timestamp":1,"currency":"USD","totalBalance":1,"grantedBalance":0,"toppedUpBalance":0}"""
            "usageSnapshots" -> """{"accountId":"account","timestamp":1,"records":[]}"""
            else -> """{"id":1,"type":"MANUAL","timestamp":1,"message":"$message"}"""
        }
        return """{"version":1,"exportedAt":"now","appVersion":"test","dailySummaries":${if (arrayName == "dailySummaries") "[${List(count) { item }.joinToString(",")}]" else "[]"},"rawRecords":${if (arrayName == "rawRecords") "[${List(count) { item }.joinToString(",")}]" else "[]"},"usageSnapshots":${if (arrayName == "usageSnapshots") "[${List(count) { item }.joinToString(",")}]" else "[]"},"refreshLogs":${if (arrayName == "refreshLogs") "[${List(count) { item }.joinToString(",")}]" else "[]"}}"""
    }

    private fun historyJsonWithUnknownDepth(depth: Int): String {
        val nested = "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1)
        return """{"version":1,"exportedAt":"now","appVersion":"test","unknown":$nested,"dailySummaries":[],"rawRecords":[],"usageSnapshots":[],"refreshLogs":[]}"""
    }

    private open class EmptyConsumer : HistoryJsonConsumer {
        override suspend fun dailySummaries(items: List<DailySummary>): Int = items.size
        override suspend fun rawRecords(items: List<RawRecord>): Int = items.size
        override suspend fun usageSnapshots(items: List<UsageSnapshot>): Int = items.size
        override suspend fun refreshLogs(items: List<RefreshLogEntry>): Int = items.size
    }

    private open class GeneratedRawSource(private val count: Int) : HistoryExportSource {
        val requestedPageSizes = mutableListOf<Int>()

        override suspend fun dailySummaryPage(offset: Int, limit: Int) = emptyList<DailySummary>()

        override suspend fun rawRecordPage(after: HistoryCursor?, limit: Int): HistoryPage {
            requestedPageSizes += limit
            val start = after?.id?.toInt() ?: 0
            val end = minOf(start + limit, count)
            val rows = (start until end).map { ordinal ->
                HistoryRecord(
                    id = (ordinal + 1).toLong(),
                    value = RawRecord("account", ordinal.toLong(), "USD", 1f, 0f, 1f)
                )
            }
            return HistoryPage(rows, rows.lastOrNull()?.let { HistoryCursor(it.value.timestamp, it.id) })
        }

        override suspend fun usageSnapshotPage(offset: Int, limit: Int) = emptyList<UsageSnapshot>()

        override suspend fun refreshLogPage(after: HistoryLogCursor?, limit: Int) =
            HistoryLogPage(emptyList(), null)
    }

    private class UsageSource(private val snapshot: UsageSnapshot) : HistoryExportSource {
        override suspend fun dailySummaryPage(offset: Int, limit: Int) = emptyList<DailySummary>()
        override suspend fun rawRecordPage(after: HistoryCursor?, limit: Int) = HistoryPage(emptyList(), null)
        override suspend fun usageSnapshotPage(offset: Int, limit: Int) =
            if (offset == 0) listOf(snapshot) else emptyList()
        override suspend fun refreshLogPage(after: HistoryLogCursor?, limit: Int) = HistoryLogPage(emptyList(), null)
    }

    private class MemoryUriStorage(
        initial: ByteArray,
        val atomicReplace: Boolean = false,
        private val failEveryOutputAfter: Int? = null
    ) : HistoryUriStorage {
        private var bytes = initial.copyOf()
        var outputOpenCount = 0
            private set
        var deleteCount = 0
            private set

        override fun openInput(uri: Uri) = ByteArrayInputStream(bytes)

        override fun openOutput(uri: Uri): OutputStream {
            outputOpenCount++
            val target = ByteArrayOutputStream()
            bytes = byteArrayOf()
            return object : OutputStream() {
                private var written = 0
                override fun write(value: Int) {
                    if (failEveryOutputAfter != null && written >= failEveryOutputAfter) {
                        throw java.io.IOException("injected publication failure")
                    }
                    target.write(value)
                    bytes = target.toByteArray()
                    written++
                }
            }
        }

        override suspend fun replaceAtomically(uri: Uri, staged: File): Boolean? {
            if (!atomicReplace) return null
            bytes = staged.readBytes()
            return true
        }

        override fun delete(uri: Uri): Boolean {
            deleteCount++
            bytes = byteArrayOf()
            return true
        }

        fun text() = bytes.toString(Charsets.UTF_8)
    }

    private fun completeJson(
        version: Int = 1,
        rawRecords: String = "[]",
        duplicateRawRecords: String? = null,
        usageSnapshots: String = "[]"
    ): String = buildString {
        append("{\"version\":$version,\"exportedAt\":\"now\",\"appVersion\":\"test\",")
        append("\"dailySummaries\":[],\"rawRecords\":$rawRecords,")
        duplicateRawRecords?.let { append("\"rawRecords\":$it,") }
        append("\"usageSnapshots\":$usageSnapshots,\"refreshLogs\":[]}")
    }

    private fun rawRecordJson(timestamp: Long) =
        """{"accountId":"account","timestamp":$timestamp,"currency":"USD","totalBalance":1,"grantedBalance":0,"toppedUpBalance":1}"""

    private fun usageJson(records: List<UsageRecord>): String =
        "[{\"accountId\":\"account\",\"timestamp\":1,\"records\":[" +
            records.joinToString(",") {
                "{\"model_name\":\"${it.model_name}\",\"total_tokens\":${it.total_tokens},\"prompt_tokens\":0,\"completion_tokens\":0}"
            } + "]}]"

    private fun smallLimits(maxRawRecords: Int = 4) = HistoryJsonLimits(
        maxFileBytes = 1024 * 1024,
        maxRawRecords = maxRawRecords,
        maxSummaries = 4,
        maxUsageSnapshots = 4,
        maxRefreshLogs = 4,
        maxFieldChars = 1024,
        maxJsonDepth = 32,
        pageSize = 500,
        chunkSize = 2
    )

    private suspend fun ensureAccount(id: String) {
        database.accountDao().insertCreate(
            AccountEntity(
                id = id,
                displayOrder = 0,
                label = "History streaming account",
                providerType = ProviderType.DEEPSEEK,
                activeCredentialGeneration = "test",
                state = AccountState.VERIFIED,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
    }

    companion object {
        private val HEADER = HistoryExportHeader(1, "2026-08-09T00:00:00", "test")
        private val TEST_URI = Uri.parse("content://history-test/export.json")
    }
}
