package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

interface HistoryRepository {
    suspend fun insert(records: List<RawRecord>, source: BalanceRecordSource): Int

    suspend fun page(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        after: HistoryCursor? = null,
        limit: Int = MAX_PAGE_SIZE
    ): HistoryPage

    suspend fun aggregate(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): HistoryAggregate?

    suspend fun count(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): Long

    suspend fun distinctCurrencies(): List<String>

    suspend fun summaries(
        accountId: String? = null,
        currency: String? = null,
        fromDateInclusive: String? = null,
        toDateInclusive: String? = null
    ): List<DailySummary>

    suspend fun upsertSummaries(summaries: List<DailySummary>)

    companion object {
        const val MAX_PAGE_SIZE = 200
        const val INSERT_CHUNK_SIZE = 500
    }
}

/** Compatibility seam for callers still backed by the legacy JSON stores. */
class LegacyHistoryRepository(
    context: Context
) : HistoryRepository {
    private val appContext = context.applicationContext

    override suspend fun insert(records: List<RawRecord>, source: BalanceRecordSource): Int =
        withContext(Dispatchers.IO) {
            when (val result = RawRecordStore.addRecords(appContext, records)) {
                is StoreWriteResult.Written -> result.itemCount
                is StoreWriteResult.Failed -> error("${result.operation}: ${result.reason}")
            }
        }

    override suspend fun page(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        after: HistoryCursor?,
        limit: Int
    ): HistoryPage = withContext(Dispatchers.IO) {
        val canonicalCurrency = currency.uppercase(Locale.ROOT)
        val ordered = RawRecordStore.getAllRecordsForAccount(appContext, accountId)
            .filter {
                it.currency.uppercase(Locale.ROOT) == canonicalCurrency &&
                    it.timestamp >= fromInclusive && it.timestamp < toExclusive
            }
            .sortedByDescending { it.timestamp }
        val identified = ordered.mapIndexed { index, record ->
            HistoryRecord(id = (ordered.size - index).toLong(), value = record)
        }
        val selected = identified.asSequence()
            .filter { row ->
                after == null || row.value.timestamp < after.recordedAt ||
                    (row.value.timestamp == after.recordedAt && row.id < after.id)
            }
            .take(limit.coerceAtMost(HistoryRepository.MAX_PAGE_SIZE))
            .toList()
        HistoryPage(
            records = selected,
            nextCursor = selected.lastOrNull()?.let { HistoryCursor(it.value.timestamp, it.id) }
        )
    }

    override suspend fun aggregate(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): HistoryAggregate? = withContext(Dispatchers.IO) {
        val canonicalCurrency = currency.uppercase(Locale.ROOT)
        val records = RawRecordStore.getAllRecordsForAccount(appContext, accountId).filter {
            it.currency.uppercase(Locale.ROOT) == canonicalCurrency &&
                it.timestamp >= fromInclusive && it.timestamp < toExclusive
        }
        val summary = RecordAggregator.aggregate(records, "legacy").singleOrNull()
            ?: return@withContext null
        summary.toHistoryAggregate()
    }

    override suspend fun count(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): Long = withContext(Dispatchers.IO) {
        val canonicalCurrency = currency.uppercase(Locale.ROOT)
        RawRecordStore.getAllRecordsForAccount(appContext, accountId).count {
            it.currency.uppercase(Locale.ROOT) == canonicalCurrency &&
                it.timestamp >= fromInclusive && it.timestamp < toExclusive
        }.toLong()
    }

    override suspend fun distinctCurrencies(): List<String> = withContext(Dispatchers.IO) {
        RawRecordStore.getAllRecords(appContext)
            .map { it.currency.uppercase(Locale.ROOT) }
            .distinct()
            .sorted()
    }

    override suspend fun summaries(
        accountId: String?,
        currency: String?,
        fromDateInclusive: String?,
        toDateInclusive: String?
    ): List<DailySummary> = withContext(Dispatchers.IO) {
        val canonicalCurrency = currency?.uppercase(Locale.ROOT)
        DailySummaryStore.getSummaries(appContext).filter { summary ->
            (accountId == null || summary.accountId == accountId) &&
                (canonicalCurrency == null || summary.currency.uppercase(Locale.ROOT) == canonicalCurrency) &&
                (fromDateInclusive == null || summary.date >= fromDateInclusive) &&
                (toDateInclusive == null || summary.date <= toDateInclusive)
        }
    }

    override suspend fun upsertSummaries(summaries: List<DailySummary>) {
        withContext(Dispatchers.IO) {
            when (val result = DailySummaryStore.addSummaries(appContext, summaries)) {
                is StoreWriteResult.Written -> Unit
                is StoreWriteResult.Failed -> error("${result.operation}: ${result.reason}")
            }
        }
    }
}

private fun DailySummary.toHistoryAggregate() = HistoryAggregate(
    accountId = accountId,
    currency = currency,
    open = open,
    close = close,
    consumed = consumed,
    toppedUp = toppedUp,
    granted = granted,
    avgBalance = avgBalance,
    sampleCount = sampleCount,
    toppedUpBalanceClose = toppedUpBalanceClose,
    grantedBalanceClose = grantedBalanceClose
)
