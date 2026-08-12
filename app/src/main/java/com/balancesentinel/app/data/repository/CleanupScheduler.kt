package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.local.history.HistorySeriesKeyProjection
import com.balancesentinel.app.data.model.DailySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class CleanupReport(
    val archivedDates: Set<String>,
    val deletedRecordCount: Int,
    val retainedRecordCount: Int,
    val failures: List<CleanupFailure>
)

data class CleanupFailure(
    val date: String,
    val stage: CleanupStage,
    val reason: String
)

enum class CleanupStage {
    READ_SOURCE,
    WRITE_SUMMARY,
    VERIFY_SUMMARY,
    DELETE_SOURCE
}

object CleanupScheduler {

    private const val RETENTION_MS = 24 * 3_600_000L
    private const val MAX_REASON_LENGTH = 160

    suspend fun runCleanup(context: Context): CleanupReport = withContext(Dispatchers.IO) {
        runRoomCleanup(context, System.currentTimeMillis(), ZoneId.systemDefault())
    }

    suspend fun runCleanup(
        context: Context,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CleanupReport = withContext(Dispatchers.IO) {
        runRoomCleanup(context, now, zoneId)
    }

    suspend fun runCleanup(
        context: Context,
        now: Long,
        zoneId: ZoneId,
        historyRepository: HistoryRepository
    ): CleanupReport = withContext(Dispatchers.IO) {
        if (historyRepository is RoomHistoryRepository) runRoomCleanup(context, now, zoneId, historyRepository)
        else runCleanupInternal(context, now, zoneId, historyRepository)
    }

    /** Runs the same cleanup pipeline for one date, preserving date-order retries. */
    suspend fun runCleanupForDate(
        context: Context,
        date: LocalDate,
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CleanupReport = withContext(Dispatchers.IO) {
        runRoomCleanup(context, now, zoneId, onlyDate = date.toString())
    }

    suspend fun runCleanupForDate(
        context: Context,
        date: LocalDate,
        now: Long,
        zoneId: ZoneId,
        historyRepository: HistoryRepository
    ): CleanupReport = withContext(Dispatchers.IO) {
        if (historyRepository is RoomHistoryRepository) {
            runRoomCleanup(context, now, zoneId, historyRepository, onlyDate = date.toString())
        } else {
            runCleanupInternal(context, now, zoneId, historyRepository, onlyDate = date.toString())
        }
    }

    private suspend fun runRoomCleanup(
        context: Context,
        now: Long,
        zoneId: ZoneId,
        repository: RoomHistoryRepository = RoomHistoryRepository(
            com.balancesentinel.app.data.local.WalletDatabaseProvider.get(context)
        ),
        onlyDate: String? = null
    ): CleanupReport {
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val archived = linkedSetOf<String>()
        val failures = mutableListOf<CleanupFailure>()
        var deleted = 0
        val cutoff = now - RETENTION_MS
        val todayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

        suspend fun archiveDate(date: LocalDate) {
            if (date == today) return
            val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            var after: HistorySeriesKeyProjection? = null
            while (true) {
                val keys = repository.rawSeriesKeyPage(from, to, after)
                if (keys.isEmpty()) break
                keys.forEach { key ->
                    try {
                        val result = repository.archiveDateSeries(
                            date = date.toString(),
                            accountId = key.accountId,
                            currency = key.currency,
                            fromInclusive = from,
                            toExclusive = to,
                            cutoff = cutoff,
                            generatedAt = now
                        )
                        if (result.hadRecords) archived += date.toString()
                        deleted += result.deletedRecords
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Exception) {
                        failures += failure(
                            date.toString(),
                            CleanupStage.WRITE_SUMMARY,
                            throwable.message ?: "Room transaction failed"
                        )
                    }
                }
                after = keys.last()
                if (keys.size < HistoryRepository.MAX_PAGE_SIZE) break
            }
        }

        if (onlyDate != null) {
            archiveDate(LocalDate.parse(onlyDate))
        } else {
            var searchFrom = Long.MIN_VALUE
            while (searchFrom < todayStart) {
                val timestamp = repository.nextRecordedAt(searchFrom, todayStart) ?: break
                val date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
                archiveDate(date)
                val nextStart = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                searchFrom = if (nextStart > searchFrom) nextStart else timestamp + 1
            }
        }
        try {
            deleted += repository.purgeExpiredSummarizedRecords(now, zoneId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            failures += failure(
                date = onlyDate ?: today.toString(),
                stage = CleanupStage.DELETE_SOURCE,
                reason = throwable.message ?: "Expired raw sweep failed"
            )
        }
        if (onlyDate == null) {
            repository.fillContinuityThrough(today.minusDays(1), now, zoneId)
        }
        val retained = repository.countRecords().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return CleanupReport(archived, deleted, retained, failures)
    }

    private suspend fun runCleanupInternal(
        context: Context,
        now: Long,
        zoneId: ZoneId,
        historyRepository: HistoryRepository? = null,
        onlyDate: String? = null
    ): CleanupReport {
        val repositoryRecords = historyRepository?.let { readAllHistory(it) }
        return DataMutationCoordinator.withMutation {
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val archivedDates = linkedSetOf<String>()
        val failures = mutableListOf<CleanupFailure>()
        var deletedRecordCount = 0

        val sourceDates = if (repositoryRecords != null) {
            repositoryRecords.map { dateOf(it.timestamp, zoneId) }.distinct()
        } else {
            try {
                RawRecordStore.getDistinctDatesForCleanup(context, zoneId)
            } catch (_: Exception) {
                failures += failure(
                    date = today.toString(),
                    stage = CleanupStage.READ_SOURCE,
                    reason = "LIST_DATES: source read failed"
                )
                emptyList()
            }
        }

        sourceDates.filter { it != today.toString() && (onlyDate == null || it == onlyDate) }.sorted().forEach { date ->
            val snapshot = if (repositoryRecords != null) {
                repositoryRecords.filter { dateOf(it.timestamp, zoneId) == date }
            } else {
                try {
                    RawRecordStore.getRecordsForDateForCleanup(context, date, zoneId)
                } catch (_: Exception) {
                    failures += failure(
                        date = date,
                        stage = CleanupStage.READ_SOURCE,
                        reason = "READ_DATE: source read failed"
                    )
                    return@forEach
                }
            }
            if (snapshot.isEmpty()) return@forEach

            val normalizedSource = snapshot.map { record ->
                val canonical = canonicalCurrency(record.currency)
                if (record.currency == canonical) record else record.copy(currency = canonical)
            }
            val recomputed = RecordAggregator.aggregate(normalizedSource, date)
                .map { it.canonicalized() }

            when (val write = DailySummaryStore.replaceForDate(context, date, recomputed)) {
                is StoreWriteResult.Failed -> {
                    failures += failure(
                        date = date,
                        stage = CleanupStage.WRITE_SUMMARY,
                        reason = writeReason(write)
                    )
                    return@forEach
                }

                is StoreWriteResult.Written -> Unit
            }

            val readback = try {
                DailySummaryStore.getSummariesForCleanup(context)
            } catch (_: Exception) {
                failures += failure(
                    date = date,
                    stage = CleanupStage.VERIFY_SUMMARY,
                    reason = "READBACK: summary read failed"
                )
                return@forEach
            }
            if (!matchesRecomputation(recomputed, readback)) {
                failures += failure(
                    date = date,
                    stage = CleanupStage.VERIFY_SUMMARY,
                    reason = "READBACK_MISMATCH: persisted summary did not match recomputation"
                )
                return@forEach
            }

            archivedDates += date
            val retentionBoundary = now - RETENTION_MS
            if (!snapshot.all { it.timestamp < retentionBoundary }) return@forEach

            when (val deletion = RawRecordStore.removeExact(context, snapshot)) {
                is StoreWriteResult.Written -> deletedRecordCount += deletion.itemCount
                is StoreWriteResult.Failed -> failures += failure(
                    date = date,
                    stage = CleanupStage.DELETE_SOURCE,
                    reason = writeReason(deletion)
                )
            }
        }

        if (onlyDate == null) fillContinuity(context, today, failures)

        val retainedRecordCount = if (repositoryRecords != null) {
            repositoryRecords.size
        } else {
            try {
                RawRecordStore.getAllRecordsForCleanup(context).size
            } catch (_: Exception) {
                failures += failure(
                    date = today.toString(),
                    stage = CleanupStage.READ_SOURCE,
                    reason = "COUNT_RETAINED: source read failed"
                )
                0
            }
        }
        CleanupReport(
            archivedDates = archivedDates,
            deletedRecordCount = deletedRecordCount,
            retainedRecordCount = retainedRecordCount,
            failures = failures
        )
        }
    }

    private suspend fun readAllHistory(repository: HistoryRepository): List<com.balancesentinel.app.data.model.RawRecord> {
        val records = mutableListOf<com.balancesentinel.app.data.model.RawRecord>()
        var cursor: HistoryCursor? = null
        while (true) {
            val page = repository.pageAll(
                after = cursor,
                limit = HistoryRepository.MAX_PAGE_SIZE
            )
            if (page.records.isEmpty()) break
            records += page.records.map { it.value }
            val next = page.nextCursor ?: break
            if (next == cursor) break
            cursor = next
        }
        return records
    }

    private fun dateOf(timestamp: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString()

    private fun fillContinuity(
        context: Context,
        today: LocalDate,
        failures: MutableList<CleanupFailure>
    ) {
        val targetDate = today.minusDays(1)
        val summaries = try {
            DailySummaryStore.getSummariesForCleanup(context)
        } catch (_: Exception) {
            failures += failure(
                date = targetDate.toString(),
                stage = CleanupStage.WRITE_SUMMARY,
                reason = "ENSURE_CONTINUITY: summary read failed"
            )
            return
        }
        val earliestDate = summaries.minOfOrNull { it.date } ?: return
        when (
            val result = DailySummaryStore.ensureContinuity(
                context = context,
                fromDate = earliestDate,
                toDate = targetDate.toString(),
                today = today
            )
        ) {
            is StoreWriteResult.Written -> Unit
            is StoreWriteResult.Failed -> failures += failure(
                date = targetDate.toString(),
                stage = CleanupStage.WRITE_SUMMARY,
                reason = "ENSURE_CONTINUITY: ${result.reason}"
            )
        }
    }

    private fun matchesRecomputation(
        expected: List<DailySummary>,
        persisted: List<DailySummary>
    ): Boolean {
        val expectedKeys = expected.mapTo(mutableSetOf()) { it.key() }
        val actualForKeys = persisted.filter { it.key() in expectedKeys }
        return expected.normalizedForVerification() == actualForKeys.normalizedForVerification()
    }

    private fun List<DailySummary>.normalizedForVerification(): List<DailySummary> =
        map { it.canonicalized().copy(generatedAt = 0L) }
            .sortedWith(compareBy({ it.date }, { it.accountId }, { it.currency }))

    private fun DailySummary.canonicalized(): DailySummary {
        val canonical = canonicalCurrency(currency)
        return if (currency == canonical) this else copy(currency = canonical)
    }

    private fun DailySummary.key() = SummaryKey(
        date = date,
        accountId = accountId,
        currency = canonicalCurrency(currency)
    )

    private fun canonicalCurrency(currency: String): String = currency.uppercase(Locale.ROOT)

    private fun writeReason(result: StoreWriteResult.Failed): String =
        "${result.operation}: ${result.reason}".take(MAX_REASON_LENGTH)

    private fun failure(
        date: String,
        stage: CleanupStage,
        reason: String
    ) = CleanupFailure(date, stage, reason.take(MAX_REASON_LENGTH))

    private data class SummaryKey(
        val date: String,
        val accountId: String,
        val currency: String
    )
}
