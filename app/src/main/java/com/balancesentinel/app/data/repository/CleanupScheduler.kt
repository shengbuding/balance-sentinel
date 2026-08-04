package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.model.DailySummary
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
        runCleanupInternal(
            context = context,
            now = System.currentTimeMillis(),
            zoneId = ZoneId.systemDefault()
        )
    }

    suspend fun runCleanup(
        context: Context,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CleanupReport = withContext(Dispatchers.IO) {
        runCleanupInternal(context, now, zoneId)
    }

    private fun runCleanupInternal(
        context: Context,
        now: Long,
        zoneId: ZoneId
    ): CleanupReport = DataMutationCoordinator.withMutation {
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val archivedDates = linkedSetOf<String>()
        val failures = mutableListOf<CleanupFailure>()
        var deletedRecordCount = 0

        val sourceDates = try {
            RawRecordStore.getDistinctDatesForCleanup(context, zoneId)
        } catch (_: Exception) {
            failures += failure(
                date = today.toString(),
                stage = CleanupStage.READ_SOURCE,
                reason = "LIST_DATES: source read failed"
            )
            emptyList()
        }

        sourceDates.filter { it != today.toString() }.sorted().forEach { date ->
            val snapshot = try {
                RawRecordStore.getRecordsForDateForCleanup(context, date, zoneId)
            } catch (_: Exception) {
                failures += failure(
                    date = date,
                    stage = CleanupStage.READ_SOURCE,
                    reason = "READ_DATE: source read failed"
                )
                return@forEach
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

        fillContinuity(context, today, failures)

        val retainedRecordCount = try {
            RawRecordStore.getAllRecordsForCleanup(context).size
        } catch (_: Exception) {
            failures += failure(
                date = today.toString(),
                stage = CleanupStage.READ_SOURCE,
                reason = "COUNT_RETAINED: source read failed"
            )
            0
        }
        CleanupReport(
            archivedDates = archivedDates,
            deletedRecordCount = deletedRecordCount,
            retainedRecordCount = retainedRecordCount,
            failures = failures
        )
    }

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
