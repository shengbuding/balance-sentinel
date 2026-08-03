package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

object RawRecordStore {

    private const val TAG = "RawRecordStore"
    private const val PREFS_NAME = "raw_records"
    private const val KEY_RECORDS = "records"
    private const val DAY_MS = 24 * 3_600_000L

    const val MAX_RECORDS = 90_000

    private val storeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RawRecord.serializer())
    private val pendingRemovalSerializer = PendingRemoval.serializer()

    fun addRecords(context: Context, records: List<RawRecord>): StoreWriteResult {
        if (records.isEmpty()) return StoreWriteResult.Written(0)
        return synchronized(storeLock) {
            try {
                addRecordsLocked(context, records)
            } catch (_: Exception) {
                Logger.w(TAG, "ADD_RECORDS failed")
                failed("ADD_RECORDS")
            }
        }
    }

    fun addRecord(context: Context, record: RawRecord) {
        addRecords(context, listOf(record))
    }

    fun getTodayRecords(context: Context): List<RawRecord> {
        val zoneId = ZoneId.systemDefault()
        val today = Instant.now().atZone(zoneId).toLocalDate().toString()
        return readOrEmpty("READ_TODAY") {
            getRecordsInternal(context).filter { dateOf(it.timestamp, zoneId) == today }
        }
    }

    fun getAllRecords(context: Context): List<RawRecord> = readOrEmpty("READ_ALL") {
        getRecordsInternal(context)
    }

    fun getTodayRecordsForAccount(context: Context, accountId: String): List<RawRecord> {
        val zoneId = ZoneId.systemDefault()
        val today = Instant.now().atZone(zoneId).toLocalDate().toString()
        return readOrEmpty("READ_TODAY_ACCOUNT") {
            getRecordsInternal(context).filter {
                it.accountId == accountId && dateOf(it.timestamp, zoneId) == today
            }
        }
    }

    fun getAllRecordsForAccount(context: Context, accountId: String): List<RawRecord> =
        readOrEmpty("READ_ACCOUNT") {
            getRecordsInternal(context).filter { it.accountId == accountId }
        }

    fun clear(context: Context) {
        synchronized(storeLock) {
            check(getPrefs(context).edit().remove(KEY_RECORDS).commit())
        }
    }

    fun removeRecords(context: Context, recordsToRemove: List<RawRecord>) {
        removeExact(context, recordsToRemove)
    }

    fun removeExact(context: Context, snapshot: List<RawRecord>): StoreWriteResult {
        if (snapshot.isEmpty()) return StoreWriteResult.Written(0)
        return synchronized(storeLock) {
            try {
                val initialState = getRecordsState(context)
                if (initialState.pendingRemoval != null) {
                    if (!persistRecords(context, initialState.records)) {
                        return@synchronized failed("REMOVE_EXACT")
                    }
                }
                val remainingCounts = snapshot.groupingBy { it }.eachCount().toMutableMap()
                val existing = initialState.records
                var removed = 0
                val remaining = existing.filter { record ->
                    val count = remainingCounts[record] ?: 0
                    if (count > 0) {
                        if (count == 1) remainingCounts.remove(record)
                        else remainingCounts[record] = count - 1
                        removed += 1
                        false
                    } else {
                        true
                    }
                }
                if (removed == 0) return@synchronized StoreWriteResult.Written(0)

                val deletionCommitted = persistPendingRemoval(context, existing, remaining)
                val stateAfterDeletion = getRecordsState(context)
                if (!deletionCommitted) {
                    if (stateAfterDeletion.pendingRemoval != null) {
                        persistRecords(context, stateAfterDeletion.records)
                    }
                    return@synchronized failed("REMOVE_EXACT")
                }

                if (stateAfterDeletion.pendingRemoval == null) {
                    return@synchronized if (stateAfterDeletion.records == remaining) {
                        StoreWriteResult.Written(removed)
                    } else {
                        failed("REMOVE_EXACT")
                    }
                }

                if (!persistRecords(context, remaining)) {
                    val finalState = getRecordsState(context)
                    return@synchronized if (
                        finalState.pendingRemoval == null && finalState.records == remaining
                    ) {
                        StoreWriteResult.Written(removed)
                    } else {
                        failed("REMOVE_EXACT")
                    }
                }
                StoreWriteResult.Written(removed)
            } catch (_: Exception) {
                Logger.w(TAG, "REMOVE_EXACT failed")
                failed("REMOVE_EXACT")
            }
        }
    }

    fun removeByAccountId(context: Context, accountId: String) {
        synchronized(storeLock) {
            val remaining = getRecordsInternal(context).filter { it.accountId != accountId }
            check(persistRecords(context, remaining))
        }
    }

    fun getRecordsSince(context: Context, timestamp: Long): List<RawRecord> =
        readOrEmpty("READ_SINCE") {
            getRecordsInternal(context).filter { it.timestamp >= timestamp }
        }

    fun getRecordsForDate(context: Context, date: String): List<RawRecord> =
        getRecordsForDate(context, date, ZoneId.systemDefault())

    fun getRecordsForDate(
        context: Context,
        date: String,
        zoneId: ZoneId
    ): List<RawRecord> = readOrEmpty("READ_DATE") {
        getRecordsInternal(context).filter { dateOf(it.timestamp, zoneId) == date }
    }

    fun getDistinctDates(context: Context): List<String> =
        getDistinctDates(context, ZoneId.systemDefault())

    fun getDistinctDates(context: Context, zoneId: ZoneId): List<String> =
        readOrEmpty("READ_DATES") {
            getRecordsInternal(context).map { dateOf(it.timestamp, zoneId) }.distinct()
        }

    internal fun getRecordsForDateForCleanup(
        context: Context,
        date: String,
        zoneId: ZoneId
    ): List<RawRecord> = synchronized(storeLock) {
        getRecordsInternal(context).filter { dateOf(it.timestamp, zoneId) == date }.toList()
    }

    internal fun getDistinctDatesForCleanup(context: Context, zoneId: ZoneId): List<String> =
        synchronized(storeLock) {
            getRecordsInternal(context).map { dateOf(it.timestamp, zoneId) }.distinct()
        }

    internal fun getAllRecordsForCleanup(context: Context): List<RawRecord> =
        synchronized(storeLock) { getRecordsInternal(context).toList() }

    fun removeByDate(context: Context, date: String, minAgeMs: Long = DAY_MS) {
        synchronized(storeLock) {
            try {
                val now = System.currentTimeMillis()
                val zoneId = ZoneId.systemDefault()
                val existing = getRecordsInternal(context)
                val remaining = existing.filter { record ->
                    dateOf(record.timestamp, zoneId) != date || now - record.timestamp < minAgeMs
                }
                if (remaining.size != existing.size && !persistRecords(context, remaining)) {
                    Logger.w(TAG, "REMOVE_DATE failed")
                }
            } catch (_: Exception) {
                Logger.w(TAG, "REMOVE_DATE failed")
            }
        }
    }

    internal fun snapshotRecords(context: Context): List<RawRecord> = synchronized(storeLock) {
        getRecordsInternal(context).toList()
    }

    internal fun restoreRecords(context: Context, snapshot: List<RawRecord>) {
        synchronized(storeLock) {
            check(persistRecords(context, snapshot))
        }
    }

    fun migrateAccountIds(context: Context, migrationMap: Map<String, String>) {
        if (migrationMap.isEmpty()) return
        synchronized(storeLock) {
            val existing = getRecordsInternal(context)
            val migrated = existing.map { record ->
                migrationMap[record.accountId]?.let { record.copy(accountId = it) } ?: record
            }
            if (migrated != existing) {
                check(persistRecords(context, migrated))
                Logger.i(TAG, "Migrated ${migrationMap.size} account IDs in RawRecordStore")
            }
        }
    }

    private fun addRecordsLocked(
        context: Context,
        records: List<RawRecord>
    ): StoreWriteResult {
        val existing = getRecordsInternal(context).toMutableList()
        existing.addAll(records)
        if (existing.size > MAX_RECORDS) {
            existing.subList(0, existing.size - MAX_RECORDS).clear()
        }
        if (!persistRecords(context, existing)) return failed("ADD_RECORDS")
        return StoreWriteResult.Written(minOf(records.size, MAX_RECORDS))
    }

    private fun getRecordsInternal(context: Context): List<RawRecord> {
        return getRecordsState(context).records
    }

    private fun getRecordsState(context: Context): RecordsState {
        val raw = getPrefs(context).getString(KEY_RECORDS, null)
            ?: return RecordsState(emptyList())
        if (raw.trimStart().startsWith("[")) {
            return RecordsState(json.decodeFromString(serializer, raw))
        }
        val pending = json.decodeFromString(pendingRemovalSerializer, raw)
        return RecordsState(records = pending.before, pendingRemoval = pending)
    }

    private fun persistRecords(context: Context, records: List<RawRecord>): Boolean {
        val serialized = json.encodeToString(serializer, records)
        return getPrefs(context).edit().putString(KEY_RECORDS, serialized).commit()
    }

    private fun persistPendingRemoval(
        context: Context,
        before: List<RawRecord>,
        after: List<RawRecord>
    ): Boolean {
        val serialized = json.encodeToString(
            pendingRemovalSerializer,
            PendingRemoval(before = before, after = after)
        )
        return getPrefs(context).edit().putString(KEY_RECORDS, serialized).commit()
    }

    private data class RecordsState(
        val records: List<RawRecord>,
        val pendingRemoval: PendingRemoval? = null
    )

    @Serializable
    private data class PendingRemoval(
        val before: List<RawRecord>,
        val after: List<RawRecord>
    )

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dateOf(timestamp: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString()

    private inline fun <T> readOrEmpty(operation: String, read: () -> List<T>): List<T> =
        try {
            read()
        } catch (_: Exception) {
            Logger.w(TAG, "$operation failed")
            emptyList()
        }

    private fun failed(operation: String) = StoreWriteResult.Failed(
        operation = operation,
        reason = "SharedPreferences commit failed"
    )
}
