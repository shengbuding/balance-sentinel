package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.io.HistoryExportHeader
import com.balancesentinel.app.data.io.HistoryExportSource
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStreamingLargeDatasetTest {
    @Test
    fun `ninety thousand records export in pages and re-import in fixed chunks`() = runBlocking {
        val source = GeneratedRawSource(90_000)
        val output = ByteArrayOutputStream()

        val written = HistoryJsonWriter().write(output, HEADER, source)

        assertEquals(90_000, written.rawRecords)
        assertTrue(source.requestedPageSizes.isNotEmpty())
        assertTrue(source.requestedPageSizes.all { it == HistoryJsonLimits.PAGE_SIZE })

        val imported = mutableSetOf<Long>()
        val batchSizes = mutableListOf<Int>()
        val result = HistoryJsonReader().read(
            ByteArrayInputStream(output.toByteArray()),
            object : EmptyConsumer() {
                override suspend fun rawRecords(items: List<RawRecord>): Int {
                    batchSizes += items.size
                    imported += items.map { it.timestamp }
                    return items.size
                }
            }
        )

        assertEquals(90_000, result.recordsInFile)
        assertEquals(90_000, imported.size)
        assertTrue(batchSizes.all { it in 1..HistoryJsonLimits.CHUNK_SIZE })
        assertEquals(HistoryJsonLimits.CHUNK_SIZE, batchSizes.first())
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
            assertRejectedWithoutPublication(plusOne, limits)
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
            maxFieldChars = 8,
            maxJsonDepth = 4,
            pageSize = 500,
            chunkSize = 2
        )

        assertEquals(1, readPublishedCount(historyJson("refreshLogs", 1, "12345678"), base))
        assertRejectedWithoutPublication(historyJson("refreshLogs", 1, "123456789"), base)

        val depthBoundary = historyJsonWithUnknownDepth(4)
        assertEquals(0, readPublishedCount(depthBoundary, base))
        assertRejectedWithoutPublication(historyJsonWithUnknownDepth(5), base)

        val exactBytes = historyJson("rawRecords", 1).toByteArray().size.toLong()
        assertEquals(1, readPublishedCount(historyJson("rawRecords", 1), base.copy(maxFileBytes = exactBytes)))
        assertRejectedWithoutPublication(
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

    private suspend fun assertRejectedWithoutPublication(json: String, limits: HistoryJsonLimits) {
        val published = mutableListOf<Any>()
        val staged = mutableListOf<Any>()
        val failure = runCatching {
            HistoryJsonReader(limits).read(ByteArrayInputStream(json.toByteArray()), object : EmptyConsumer() {
                override suspend fun dailySummaries(items: List<DailySummary>) = stage(items, staged)
                override suspend fun rawRecords(items: List<RawRecord>) = stage(items, staged)
                override suspend fun usageSnapshots(items: List<UsageSnapshot>) = stage(items, staged)
                override suspend fun refreshLogs(items: List<RefreshLogEntry>) = stage(items, staged)
            })
            published.addAll(staged)
        }.exceptionOrNull()
        assertTrue("expected bounded parser rejection", failure is IllegalArgumentException)
        assertTrue("failed input must not publish staged rows", published.isEmpty())
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

    private class GeneratedRawSource(private val count: Int) : HistoryExportSource {
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

    companion object {
        private val HEADER = HistoryExportHeader(1, "2026-08-09T00:00:00", "test")
    }
}
