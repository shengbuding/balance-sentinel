package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.history.HistoryAggregateProjection
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.Currency

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

class RoomHistoryRepository(
    private val database: WalletDatabase
) : HistoryRepository {
    override suspend fun insert(records: List<RawRecord>, source: BalanceRecordSource): Int {
        if (records.isEmpty()) return 0
        var written = 0
        records.chunked(HistoryRepository.INSERT_CHUNK_SIZE).forEach { chunk ->
            val entities = chunk.map { record ->
                BalanceRecordEntity(
                    accountId = require(record.accountId.isNotBlank()) { "accountId must not be blank" }.let { record.accountId },
                    currency = requireIsoCurrency(record.currency),
                    recordedAt = record.timestamp,
                    totalBalance = record.totalBalance.toDouble(),
                    grantedBalance = record.grantedBalance.toDouble(),
                    toppedUpBalance = record.toppedUpBalance.toDouble(),
                    source = source
                )
            }
            database.historyDao().insertBalanceBatch(entities)
            written += entities.size
        }
        return written
    }

    override suspend fun page(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        after: HistoryCursor?,
        limit: Int
    ): HistoryPage {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(toExclusive >= fromInclusive) { "invalid history range" }
        val rows = database.historyDao().keysetPage(
            accountId = accountId,
            currency = requireIsoCurrency(currency),
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            afterRecordedAt = after?.recordedAt,
            afterId = after?.id,
            limit = limit.coerceIn(1, HistoryRepository.MAX_PAGE_SIZE)
        )
        return HistoryPage(
            records = rows.map { it.toHistoryRecord() },
            nextCursor = rows.lastOrNull()?.let { HistoryCursor(it.recordedAt, it.id) }
        )
    }

    override suspend fun aggregate(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): HistoryAggregate? {
        require(toExclusive >= fromInclusive) { "invalid history range" }
        return database.historyDao().aggregateSemantic(
            accountId,
            requireIsoCurrency(currency),
            fromInclusive,
            toExclusive
        ).toHistoryAggregateOrNull(accountId, requireIsoCurrency(currency))
    }

    override suspend fun count(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): Long {
        require(toExclusive >= fromInclusive) { "invalid history range" }
        return database.historyDao().countRange(
            accountId,
            requireIsoCurrency(currency),
            fromInclusive,
            toExclusive
        )
    }

    override suspend fun distinctCurrencies(): List<String> = database.historyDao().distinctCurrencies()

    override suspend fun summaries(
        accountId: String?,
        currency: String?,
        fromDateInclusive: String?,
        toDateInclusive: String?
    ): List<DailySummary> {
        val canonicalCurrency = currency?.let(::requireIsoCurrency)
        return database.historyDao().querySummaries(
            accountId,
            canonicalCurrency,
            fromDateInclusive,
            toDateInclusive
        ).map { it.toDomain() }
    }

    override suspend fun upsertSummaries(summaries: List<DailySummary>) {
        summaries.chunked(HistoryRepository.INSERT_CHUNK_SIZE).forEach { chunk ->
            database.historyDao().upsertSummaries(chunk.map { it.toEntity() })
        }
    }
}

private fun BalanceRecordEntity.toHistoryRecord() = HistoryRecord(
    id = id,
    value = RawRecord(
        accountId = accountId,
        timestamp = recordedAt,
        currency = currency,
        totalBalance = totalBalance.toFloat(),
        grantedBalance = grantedBalance.toFloat(),
        toppedUpBalance = toppedUpBalance.toFloat()
    )
)

private fun DailySummary.toEntity() = DailySummaryEntity(
    date = date,
    accountId = accountId,
    currency = requireIsoCurrency(currency),
    openBalance = open.toDouble(),
    closeBalance = close.toDouble(),
    consumedBalance = consumed.toDouble(),
    toppedUpBalance = toppedUp.toDouble(),
    grantedBalance = granted.toDouble(),
    averageBalance = avgBalance.toDouble(),
    sampleCount = sampleCount,
    toppedUpBalanceClose = toppedUpBalanceClose.toDouble(),
    grantedBalanceClose = grantedBalanceClose.toDouble(),
    generatedAt = generatedAt
)

private fun DailySummaryEntity.toDomain() = DailySummary(
    accountId = accountId,
    date = date,
    currency = currency,
    open = openBalance.toFloat(),
    close = closeBalance.toFloat(),
    consumed = consumedBalance.toFloat(),
    toppedUp = toppedUpBalance.toFloat(),
    granted = grantedBalance.toFloat(),
    avgBalance = averageBalance.toFloat(),
    sampleCount = sampleCount,
    toppedUpBalanceClose = toppedUpBalanceClose.toFloat(),
    grantedBalanceClose = grantedBalanceClose.toFloat(),
    generatedAt = generatedAt
)

private fun HistoryAggregateProjection.toHistoryAggregateOrNull(
    accountId: String,
    currency: String
): HistoryAggregate? = if (count == 0L) null else HistoryAggregate(
    accountId = accountId,
    currency = currency,
    open = requireNotNull(openBalance).toFloat(),
    close = requireNotNull(closeBalance).toFloat(),
    consumed = (consumedBalance ?: 0.0).toFloat(),
    toppedUp = (toppedUpBalance ?: 0.0).toFloat(),
    granted = (grantedBalance ?: 0.0).toFloat(),
    avgBalance = requireNotNull(averageBalance).toFloat(),
    sampleCount = count.toInt(),
    toppedUpBalanceClose = (toppedUpBalanceClose ?: 0.0).toFloat(),
    grantedBalanceClose = (grantedBalanceClose ?: 0.0).toFloat()
)

private fun requireIsoCurrency(value: String): String {
    val canonical = value.trim().uppercase(Locale.ROOT)
    require(canonical.length == 3) { "Unknown ISO currency: $value" }
    try {
        Currency.getInstance(canonical)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown ISO currency: $value")
    }
    return canonical
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
