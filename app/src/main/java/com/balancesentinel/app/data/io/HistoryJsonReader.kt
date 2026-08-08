package com.balancesentinel.app.data.io

import android.util.JsonReader
import android.util.JsonToken
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface HistoryJsonConsumer {
    suspend fun dailySummaries(items: List<DailySummary>): Int
    suspend fun rawRecords(items: List<RawRecord>): Int
    suspend fun usageSnapshots(items: List<UsageSnapshot>): Int
    suspend fun refreshLogs(items: List<RefreshLogEntry>): Int
}

data class HistoryJsonReadResult(
    val header: HistoryExportHeader,
    val summariesInFile: Int,
    val summariesImported: Int,
    val recordsInFile: Int,
    val recordsImported: Int,
    val snapshotsInFile: Int,
    val snapshotsImported: Int,
    val logsInFile: Int,
    val logsImported: Int
)

class HistoryJsonReader(
    private val limits: HistoryJsonLimits = HistoryJsonLimits()
) {
    private var depth = 0

    suspend fun read(input: InputStream, consumer: HistoryJsonConsumer): HistoryJsonReadResult {
        depth = 0
        val reader = JsonReader(
            InputStreamReader(BoundedInputStream(input, limits.maxFileBytes), StandardCharsets.UTF_8)
        ).apply { isLenient = false }
        var version = 1
        var exportedAt = ""
        var appVersion = ""
        var summaryCounts = 0 to 0
        var recordCounts = 0 to 0
        var usageCounts = 0 to 0
        var logCounts = 0 to 0
        val seen = mutableSetOf<String>()
        val required = setOf(
            "version",
            "exportedAt",
            "appVersion",
            "dailySummaries",
            "rawRecords",
            "usageSnapshots",
            "refreshLogs"
        )

        beginObject(reader)
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            val name = nextName(reader)
            if (name in required) require(seen.add(name)) { "Duplicate top-level field: $name" }
            when (name) {
                "version" -> version = reader.nextInt()
                "exportedAt" -> exportedAt = nextString(reader)
                "appVersion" -> appVersion = nextString(reader)
                "dailySummaries" -> summaryCounts = readArray(
                    reader,
                    limits.maxSummaries,
                    ::readSummary,
                    consumer::dailySummaries
                )
                "rawRecords" -> recordCounts = readArray(
                    reader,
                    limits.maxRawRecords,
                    ::readRawRecord,
                    consumer::rawRecords
                )
                "usageSnapshots" -> usageCounts = readArray(
                    reader,
                    limits.maxUsageSnapshots,
                    ::readUsageSnapshot,
                    consumer::usageSnapshots
                )
                "refreshLogs" -> logCounts = readArray(
                    reader,
                    limits.maxRefreshLogs,
                    ::readRefreshLog,
                    consumer::refreshLogs
                )
                else -> skipValue(reader)
            }
        }
        endObject(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
        require(seen.containsAll(required)) { "Missing top-level fields: ${required - seen}" }
        require(version == SUPPORTED_VERSION) { "Unsupported history schema version: $version" }
        return HistoryJsonReadResult(
            HistoryExportHeader(version, exportedAt, appVersion),
            summaryCounts.first,
            summaryCounts.second,
            recordCounts.first,
            recordCounts.second,
            usageCounts.first,
            usageCounts.second,
            logCounts.first,
            logCounts.second
        )
    }

    private suspend fun <T> readArray(
        reader: JsonReader,
        maximum: Int,
        readItem: (JsonReader) -> T,
        consume: suspend (List<T>) -> Int
    ): Pair<Int, Int> {
        var inFile = 0
        var imported = 0
        val chunk = ArrayList<T>(limits.chunkSize)
        beginArray(reader)
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            require(inFile < maximum) { "JSON array exceeds record limit $maximum" }
            chunk += readItem(reader)
            inFile++
            if (chunk.size == limits.chunkSize) {
                imported += consume(chunk.toList())
                chunk.clear()
            }
        }
        endArray(reader)
        if (chunk.isNotEmpty()) imported += consume(chunk.toList())
        return inFile to imported
    }

    private fun readSummary(reader: JsonReader): DailySummary {
        var accountId = ""
        var date = ""
        var currency = ""
        var open = 0f
        var close = 0f
        var consumed = 0f
        var toppedUp = 0f
        var granted = 0f
        var average = 0f
        var samples = 0
        var toppedClose = 0f
        var grantedClose = 0f
        var generatedAt = 0L
        beginObject(reader)
        while (reader.hasNext()) when (nextName(reader)) {
            "accountId" -> accountId = nextString(reader)
            "date" -> date = nextString(reader)
            "currency" -> currency = nextString(reader)
            "open" -> open = reader.nextDouble().toFloat()
            "close" -> close = reader.nextDouble().toFloat()
            "consumed" -> consumed = reader.nextDouble().toFloat()
            "toppedUp" -> toppedUp = reader.nextDouble().toFloat()
            "granted" -> granted = reader.nextDouble().toFloat()
            "avgBalance" -> average = reader.nextDouble().toFloat()
            "sampleCount" -> samples = reader.nextInt()
            "toppedUpBalanceClose" -> toppedClose = reader.nextDouble().toFloat()
            "grantedBalanceClose" -> grantedClose = reader.nextDouble().toFloat()
            "generatedAt" -> generatedAt = reader.nextLong()
            else -> skipValue(reader)
        }
        endObject(reader)
        return DailySummary(accountId, date, currency, open, close, consumed, toppedUp, granted,
            average, samples, toppedClose, grantedClose, generatedAt)
    }

    private fun readRawRecord(reader: JsonReader): RawRecord {
        var accountId = ""
        var timestamp = 0L
        var currency = ""
        var total = 0f
        var granted = 0f
        var topped = 0f
        beginObject(reader)
        while (reader.hasNext()) when (nextName(reader)) {
            "accountId" -> accountId = nextString(reader)
            "timestamp" -> timestamp = reader.nextLong()
            "currency" -> currency = nextString(reader)
            "totalBalance" -> total = reader.nextDouble().toFloat()
            "grantedBalance" -> granted = reader.nextDouble().toFloat()
            "toppedUpBalance" -> topped = reader.nextDouble().toFloat()
            else -> skipValue(reader)
        }
        endObject(reader)
        return RawRecord(accountId, timestamp, currency, total, granted, topped)
    }

    private fun readUsageSnapshot(reader: JsonReader): UsageSnapshot {
        var accountId = ""
        var timestamp = 0L
        val records = mutableListOf<UsageRecord>()
        beginObject(reader)
        while (reader.hasNext()) when (nextName(reader)) {
            "accountId" -> accountId = nextString(reader)
            "timestamp" -> timestamp = reader.nextLong()
            "records" -> {
                beginArray(reader)
                while (reader.hasNext()) {
                    require(records.size < limits.maxUsageRecordsPerSnapshot) {
                        "Usage snapshot exceeds record limit ${limits.maxUsageRecordsPerSnapshot}"
                    }
                    records += readUsageRecord(reader)
                }
                endArray(reader)
            }
            else -> skipValue(reader)
        }
        endObject(reader)
        return UsageSnapshot(accountId, timestamp, records)
    }

    private fun readUsageRecord(reader: JsonReader): UsageRecord {
        var model = ""
        var total = 0L
        var prompt = 0L
        var completion = 0L
        beginObject(reader)
        while (reader.hasNext()) when (nextName(reader)) {
            "model_name" -> model = nextString(reader)
            "total_tokens" -> total = reader.nextLong()
            "prompt_tokens" -> prompt = reader.nextLong()
            "completion_tokens" -> completion = reader.nextLong()
            else -> skipValue(reader)
        }
        endObject(reader)
        return UsageRecord(model, total, prompt, completion)
    }

    private fun readRefreshLog(reader: JsonReader): RefreshLogEntry {
        var id = 0L
        var type = RefreshLogType.MANUAL
        var total = ""
        var currency = ""
        var available = false
        var granted = ""
        var topped = ""
        var timestamp = 0L
        var message = ""
        var interval = 0
        var expected = 0L
        var alarmMethod = ""
        var missReason = ""
        beginObject(reader)
        while (reader.hasNext()) when (nextName(reader)) {
            "id" -> id = reader.nextLong()
            "type" -> type = RefreshLogType.valueOf(nextString(reader))
            "totalBalance" -> total = nextString(reader)
            "currency" -> currency = nextString(reader)
            "isAvailable" -> available = reader.nextBoolean()
            "grantedBalance" -> granted = nextString(reader)
            "toppedUpBalance" -> topped = nextString(reader)
            "timestamp" -> timestamp = reader.nextLong()
            "message" -> message = nextString(reader)
            "intervalSeconds" -> interval = reader.nextInt()
            "expectedTime" -> expected = reader.nextLong()
            "alarmMethod" -> alarmMethod = nextString(reader)
            "missReason" -> missReason = nextString(reader)
            else -> skipValue(reader)
        }
        endObject(reader)
        return RefreshLogEntry(id, type, total, currency, available, granted, topped, timestamp,
            message, interval, expected, alarmMethod, missReason)
    }

    private fun nextName(reader: JsonReader): String = reader.nextName().also(::checkField)

    private fun nextString(reader: JsonReader): String = reader.nextString().also(::checkField)

    private fun checkField(value: String) {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= limits.maxFieldChars) {
            "JSON field exceeds limit ${limits.maxFieldChars}"
        }
    }

    private fun beginObject(reader: JsonReader) {
        enterDepth()
        reader.beginObject()
    }

    private fun endObject(reader: JsonReader) {
        reader.endObject()
        depth--
    }

    private fun beginArray(reader: JsonReader) {
        enterDepth()
        reader.beginArray()
    }

    private fun endArray(reader: JsonReader) {
        reader.endArray()
        depth--
    }

    private fun enterDepth() {
        require(depth < limits.maxJsonDepth) { "JSON exceeds depth limit ${limits.maxJsonDepth}" }
        depth++
    }

    private fun skipValue(reader: JsonReader) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                beginObject(reader)
                while (reader.hasNext()) {
                    nextName(reader)
                    skipValue(reader)
                }
                endObject(reader)
            }
            JsonToken.BEGIN_ARRAY -> {
                beginArray(reader)
                while (reader.hasNext()) skipValue(reader)
                endArray(reader)
            }
            JsonToken.STRING, JsonToken.NAME -> nextString(reader)
            JsonToken.NUMBER -> reader.nextString().also(::checkField)
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("Unexpected JSON token ${reader.peek()}")
        }
    }

    private companion object {
        const val SUPPORTED_VERSION = 1
    }
}

private class BoundedInputStream(
    input: InputStream,
    private val maximum: Long
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) reserve(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val value = super.read(buffer, offset, length)
        if (value > 0) reserve(value.toLong())
        return value
    }

    private fun reserve(length: Long) {
        require(length <= maximum - count) { "History JSON exceeds file limit $maximum" }
        count += length
    }
}
