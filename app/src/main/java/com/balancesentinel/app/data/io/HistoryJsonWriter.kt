package com.balancesentinel.app.data.io

import android.util.JsonWriter
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.HistoryCursor
import com.balancesentinel.app.data.repository.HistoryPage
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class HistoryJsonLimits(
    val maxFileBytes: Long = MAX_FILE_BYTES,
    val maxRawRecords: Int = MAX_RAW_RECORDS,
    val maxSummaries: Int = MAX_SUMMARIES,
    val maxUsageSnapshots: Int = MAX_USAGE_SNAPSHOTS,
    val maxRefreshLogs: Int = MAX_REFRESH_LOGS,
    val maxFieldChars: Int = MAX_FIELD_CHARS,
    val maxJsonDepth: Int = MAX_JSON_DEPTH,
    val pageSize: Int = PAGE_SIZE,
    val chunkSize: Int = CHUNK_SIZE
) {
    init {
        require(maxFileBytes > 0)
        require(maxRawRecords >= 0 && maxSummaries >= 0)
        require(maxUsageSnapshots >= 0 && maxRefreshLogs >= 0)
        require(maxFieldChars >= 0 && maxJsonDepth >= 1)
        require(pageSize > 0 && chunkSize > 0)
    }

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024L * 1024L
        const val MAX_RAW_RECORDS = 100_000
        const val MAX_SUMMARIES = 50_000
        const val MAX_USAGE_SNAPSHOTS = 10_000
        const val MAX_REFRESH_LOGS = 10_000
        const val MAX_FIELD_CHARS = 256 * 1024
        const val MAX_JSON_DEPTH = 32
        const val PAGE_SIZE = 500
        const val CHUNK_SIZE = 500
    }
}

data class HistoryExportHeader(
    val version: Int,
    val exportedAt: String,
    val appVersion: String
)

data class HistoryLogCursor(val recordedAt: Long, val id: Long)

data class HistoryLogPage(
    val logs: List<RefreshLogEntry>,
    val nextCursor: HistoryLogCursor?
)

interface HistoryExportSource {
    suspend fun dailySummaryPage(offset: Int, limit: Int): List<DailySummary>
    suspend fun rawRecordPage(after: HistoryCursor?, limit: Int): HistoryPage
    suspend fun usageSnapshotPage(offset: Int, limit: Int): List<UsageSnapshot>
    suspend fun refreshLogPage(after: HistoryLogCursor?, limit: Int): HistoryLogPage
}

data class HistoryJsonWriteCounts(
    val dailySummaries: Int,
    val rawRecords: Int,
    val usageSnapshots: Int,
    val refreshLogs: Int
)

class HistoryJsonWriter(
    private val limits: HistoryJsonLimits = HistoryJsonLimits()
) {
    suspend fun write(
        output: OutputStream,
        header: HistoryExportHeader,
        source: HistoryExportSource
    ): HistoryJsonWriteCounts {
        val bounded = BoundedOutputStream(output, limits.maxFileBytes)
        val writer = JsonWriter(OutputStreamWriter(bounded, StandardCharsets.UTF_8)).apply {
            setIndent("  ")
        }
        var summaries = 0
        var records = 0
        var snapshots = 0
        var logs = 0

        writer.beginObject()
        writer.name("version").value(header.version.toLong())
        writer.name("exportedAt").value(field(header.exportedAt))
        writer.name("appVersion").value(field(header.appVersion))

        writer.name("dailySummaries").beginArray()
        var summaryOffset = 0
        while (true) {
            val page = source.dailySummaryPage(summaryOffset, limits.pageSize)
            require(page.size <= limits.pageSize) { "Summary source exceeded page size" }
            if (page.isEmpty()) break
            page.forEach { summary ->
                summaries = checkedCount("daily summaries", summaries, limits.maxSummaries)
                writeSummary(writer, summary)
            }
            summaryOffset += page.size
        }
        writer.endArray()

        writer.name("rawRecords").beginArray()
        var historyCursor: HistoryCursor? = null
        while (true) {
            val page = source.rawRecordPage(historyCursor, limits.pageSize)
            require(page.records.size <= limits.pageSize) { "Raw source exceeded page size" }
            if (page.records.isEmpty()) break
            page.records.forEach { row ->
                records = checkedCount("raw records", records, limits.maxRawRecords)
                writeRawRecord(writer, row.value)
            }
            val next = page.nextCursor ?: break
            require(next != historyCursor) { "Raw source cursor did not advance" }
            historyCursor = next
        }
        writer.endArray()

        writer.name("usageSnapshots").beginArray()
        var usageOffset = 0
        while (true) {
            val page = source.usageSnapshotPage(usageOffset, limits.pageSize)
            require(page.size <= limits.pageSize) { "Usage source exceeded page size" }
            if (page.isEmpty()) break
            page.forEach { snapshot ->
                snapshots = checkedCount("usage snapshots", snapshots, limits.maxUsageSnapshots)
                writeUsageSnapshot(writer, snapshot)
            }
            usageOffset += page.size
        }
        writer.endArray()

        writer.name("refreshLogs").beginArray()
        var logCursor: HistoryLogCursor? = null
        while (true) {
            val page = source.refreshLogPage(logCursor, limits.pageSize)
            require(page.logs.size <= limits.pageSize) { "Log source exceeded page size" }
            if (page.logs.isEmpty()) break
            page.logs.forEach { log ->
                logs = checkedCount("refresh logs", logs, limits.maxRefreshLogs)
                writeRefreshLog(writer, log)
            }
            val next = page.nextCursor ?: break
            require(next != logCursor) { "Log source cursor did not advance" }
            logCursor = next
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
        return HistoryJsonWriteCounts(summaries, records, snapshots, logs)
    }

    private fun checkedCount(name: String, current: Int, maximum: Int): Int {
        require(current < maximum) { "$name exceeds limit $maximum" }
        return current + 1
    }

    private fun field(value: String): String {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= limits.maxFieldChars) {
            "JSON field exceeds limit ${limits.maxFieldChars}"
        }
        return value
    }

    private fun writeSummary(writer: JsonWriter, value: DailySummary) {
        writer.beginObject()
        writer.name("accountId").value(field(value.accountId))
        writer.name("date").value(field(value.date))
        writer.name("currency").value(field(value.currency))
        writer.name("open").value(value.open.toDouble())
        writer.name("close").value(value.close.toDouble())
        writer.name("consumed").value(value.consumed.toDouble())
        writer.name("toppedUp").value(value.toppedUp.toDouble())
        writer.name("granted").value(value.granted.toDouble())
        writer.name("avgBalance").value(value.avgBalance.toDouble())
        writer.name("sampleCount").value(value.sampleCount.toLong())
        writer.name("toppedUpBalanceClose").value(value.toppedUpBalanceClose.toDouble())
        writer.name("grantedBalanceClose").value(value.grantedBalanceClose.toDouble())
        writer.name("generatedAt").value(value.generatedAt)
        writer.endObject()
    }

    private fun writeRawRecord(writer: JsonWriter, value: RawRecord) {
        writer.beginObject()
        writer.name("accountId").value(field(value.accountId))
        writer.name("timestamp").value(value.timestamp)
        writer.name("currency").value(field(value.currency))
        writer.name("totalBalance").value(value.totalBalance.toDouble())
        writer.name("grantedBalance").value(value.grantedBalance.toDouble())
        writer.name("toppedUpBalance").value(value.toppedUpBalance.toDouble())
        writer.endObject()
    }

    private fun writeUsageSnapshot(writer: JsonWriter, value: UsageSnapshot) {
        writer.beginObject()
        writer.name("accountId").value(field(value.accountId))
        writer.name("timestamp").value(value.timestamp)
        writer.name("records").beginArray()
        value.records.forEach { record ->
            writer.beginObject()
            writer.name("model_name").value(field(record.model_name))
            writer.name("total_tokens").value(record.total_tokens)
            writer.name("prompt_tokens").value(record.prompt_tokens)
            writer.name("completion_tokens").value(record.completion_tokens)
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    private fun writeRefreshLog(writer: JsonWriter, value: RefreshLogEntry) {
        writer.beginObject()
        writer.name("id").value(value.id)
        writer.name("type").value(value.type.name)
        writer.name("totalBalance").value(field(value.totalBalance))
        writer.name("currency").value(field(value.currency))
        writer.name("isAvailable").value(value.isAvailable)
        writer.name("grantedBalance").value(field(value.grantedBalance))
        writer.name("toppedUpBalance").value(field(value.toppedUpBalance))
        writer.name("timestamp").value(value.timestamp)
        writer.name("message").value(field(value.message))
        writer.name("intervalSeconds").value(value.intervalSeconds.toLong())
        writer.name("expectedTime").value(value.expectedTime)
        writer.name("alarmMethod").value(field(value.alarmMethod))
        writer.name("missReason").value(field(value.missReason))
        writer.endObject()
    }
}

private class BoundedOutputStream(
    output: OutputStream,
    private val maximum: Long
) : FilterOutputStream(output) {
    private var count = 0L

    override fun write(value: Int) {
        reserve(1)
        out.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        reserve(length.toLong())
        out.write(buffer, offset, length)
    }

    private fun reserve(length: Long) {
        require(length <= maximum - count) { "History JSON exceeds file limit $maximum" }
        count += length
    }
}
