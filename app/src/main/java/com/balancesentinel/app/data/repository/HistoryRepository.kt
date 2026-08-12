package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.history.HistoryAggregateProjection
import com.balancesentinel.app.data.local.history.HistorySeriesKeyProjection
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.Currency

/** Reserved identity for a continuity placeholder, never a cleanup publication. */
internal const val CONTINUITY_SUMMARY_IDENTITY = "__continuity__"

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

    suspend fun pageAll(
        fromInclusive: Long = Long.MIN_VALUE,
        toExclusive: Long = Long.MAX_VALUE,
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

    override suspend fun pageAll(
        fromInclusive: Long,
        toExclusive: Long,
        after: HistoryCursor?,
        limit: Int
    ): HistoryPage = withContext(Dispatchers.IO) {
        val ordered = RawRecordStore.getAllRecords(appContext)
            .filter { it.timestamp >= fromInclusive && it.timestamp < toExclusive }
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

open class RoomHistoryRepository(
    private val database: WalletDatabase
) : HistoryRepository {
    internal suspend fun countRecords(): Long = database.historyDao().countRecords()

    internal open suspend fun nextRecordedAt(fromInclusive: Long, toExclusive: Long): Long? =
        database.historyDao().nextRecordedAt(fromInclusive, toExclusive)

    internal open suspend fun rawSeriesKeyPage(
        fromInclusive: Long,
        toExclusive: Long,
        after: HistorySeriesKeyProjection?,
        limit: Int = CLEANUP_KEY_PAGE_SIZE
    ): List<HistorySeriesKeyProjection> = database.historyDao().rawSeriesKeyPage(
        fromInclusive = fromInclusive,
        toExclusive = toExclusive,
        afterAccountId = after?.accountId,
        afterCurrency = after?.currency,
        limit = limit.coerceIn(1, CLEANUP_KEY_PAGE_SIZE)
    )

    internal data class CleanupArchiveResult(
        val hadRecords: Boolean,
        val deletedRecords: Int
    )

    internal open suspend fun archiveDateSeries(
        date: String,
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        cutoff: Long,
        generatedAt: Long
    ): CleanupArchiveResult = database.withTransaction {
        val canonicalCurrency = requireIsoCurrency(currency)
        val dao = database.historyDao()
        val recordCount = dao.countRecordsForDay(
            accountId,
            canonicalCurrency,
            fromInclusive,
            toExclusive
        )
        if (recordCount == 0L) {
            return@withTransaction CleanupArchiveResult(false, 0)
        }

        if (dao.countPublishedSummaryKey(
                date,
                accountId,
                canonicalCurrency,
                CONTINUITY_SUMMARY_IDENTITY
            ) == 0L
        ) {
            val aggregate = dao.aggregateSemantic(
                accountId,
                canonicalCurrency,
                fromInclusive,
                toExclusive
            ).toHistoryAggregateOrNull(accountId, canonicalCurrency)
                ?: return@withTransaction CleanupArchiveResult(false, 0)
            dao.deleteSummaryIdentity(
                date,
                accountId,
                canonicalCurrency,
                CONTINUITY_SUMMARY_IDENTITY
            )
            dao.insertSummariesIfAbsent(
                listOf(
                    DailySummary(
                        accountId = accountId,
                        date = date,
                        currency = canonicalCurrency,
                        open = aggregate.open,
                        close = aggregate.close,
                        consumed = aggregate.consumed,
                        toppedUp = aggregate.toppedUp,
                        granted = aggregate.granted,
                        avgBalance = aggregate.avgBalance,
                        sampleCount = aggregate.sampleCount,
                        toppedUpBalanceClose = aggregate.toppedUpBalanceClose,
                        grantedBalanceClose = aggregate.grantedBalanceClose,
                        generatedAt = generatedAt
                    ).toEntity()
                )
            )
        }

        CleanupArchiveResult(
            hadRecords = true,
            deletedRecords = dao.deleteExpiredForDate(
                cutoff = cutoff,
                fromInclusive = fromInclusive,
                toExclusive = toExclusive,
                accountId = accountId,
                currency = canonicalCurrency
            )
        )
    }

    internal suspend fun archiveAndDelete(
        summaries: List<DailySummary>,
        recordIds: List<Long>
    ) = database.withTransaction {
        summaries.forEach { summary ->
            database.historyDao().deleteSummaryIdentity(
                date = summary.date,
                accountId = summary.accountId,
                currency = requireIsoCurrency(summary.currency),
                identityDiscriminator = CONTINUITY_SUMMARY_IDENTITY
            )
        }
        database.historyDao().insertSummariesIfAbsent(summaries.map { it.toEntity() })
        recordIds.chunked(500).forEach { ids -> database.historyDao().deleteByIds(ids) }
    }

    /**
     * Publishes continuity placeholders without allowing a stale full cleanup
     * to race a date worker. The raw-row check and insert share one Room
     * transaction, while real cleanup rows use the empty identity and can
     * replace/remove a placeholder in their own transaction.
     */
    internal suspend fun insertContinuitySummariesIfNoRaw(
        summaries: List<DailySummary>,
        zoneId: java.time.ZoneId
    ) = database.withTransaction {
        summaries.forEach { summary ->
            val currency = requireIsoCurrency(summary.currency)
            if (database.historyDao().countPublishedSummaryKey(
                    summary.date,
                    summary.accountId,
                    currency,
                    CONTINUITY_SUMMARY_IDENTITY
                ) > 0L
            ) {
                return@forEach
            }
            val date = java.time.LocalDate.parse(summary.date)
            val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            if (database.historyDao().countRecordsForDay(summary.accountId, currency, from, to) == 0L) {
                database.historyDao().insertSummariesIfAbsent(
                    listOf(summary.toEntity(CONTINUITY_SUMMARY_IDENTITY))
                )
            }
        }
    }

    /**
     * Removes only expired raw rows for dates that already have a canonical
     * historical summary. This is separate from date aggregation so a recent
     * tail can be retained for 24 hours and deleted later without rewriting a
     * frozen summary from a partial snapshot.
     */
    internal suspend fun purgeExpiredSummarizedRecords(
        now: Long,
        zoneId: java.time.ZoneId
    ): Int {
        val cutoff = now - 24L * 3_600_000L
        val todayStart = java.time.Instant.ofEpochMilli(now)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val upperExclusive = minOf(cutoff, todayStart)
        var searchFrom = Long.MIN_VALUE
        var deleted = 0
        while (searchFrom < upperExclusive) {
            val timestamp = nextRecordedAt(searchFrom, upperExclusive) ?: break
            val date = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
            val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            var after: HistorySeriesKeyProjection? = null
            while (true) {
                val keys = rawSeriesKeyPage(from, to, after)
                if (keys.isEmpty()) break
                keys.forEach { key ->
                    deleted += database.withTransaction {
                        val canonicalCurrency = requireIsoCurrency(key.currency)
                        if (database.historyDao().countPublishedSummaryKey(
                                date.toString(),
                                key.accountId,
                                canonicalCurrency,
                                CONTINUITY_SUMMARY_IDENTITY
                            ) == 0L
                        ) {
                            0
                        } else {
                            database.historyDao().deleteExpiredForDate(
                                cutoff,
                                from,
                                to,
                                key.accountId,
                                canonicalCurrency
                            )
                        }
                    }
                }
                after = keys.last()
                if (keys.size < CLEANUP_KEY_PAGE_SIZE) break
            }
            searchFrom = if (to > searchFrom) to else timestamp + 1
        }
        return deleted
    }

    internal suspend fun fillContinuityThrough(
        throughDate: java.time.LocalDate,
        generatedAt: Long,
        zoneId: java.time.ZoneId
    ) {
        var afterSeries: HistorySeriesKeyProjection? = null
        while (true) {
            val seriesPage = database.historyDao().summarySeriesKeyPage(
                afterSeries?.accountId,
                afterSeries?.currency,
                CLEANUP_KEY_PAGE_SIZE
            )
            if (seriesPage.isEmpty()) break
            seriesPage.forEach { series ->
                fillContinuitySeries(series, throughDate, generatedAt, zoneId)
            }
            afterSeries = seriesPage.last()
            if (seriesPage.size < CLEANUP_KEY_PAGE_SIZE) break
        }
    }

    private suspend fun fillContinuitySeries(
        series: HistorySeriesKeyProjection,
        throughDate: java.time.LocalDate,
        generatedAt: Long,
        zoneId: java.time.ZoneId
    ) {
        var afterDate: String? = null
        var previous: DailySummary? = null
        val pending = ArrayList<DailySummary>(CLEANUP_KEY_PAGE_SIZE)

        suspend fun flush() {
            if (pending.isEmpty()) return
            insertContinuitySummariesIfNoRaw(pending.toList(), zoneId)
            pending.clear()
        }

        suspend fun appendGap(untilExclusive: java.time.LocalDate) {
            var cursor = previous?.let { java.time.LocalDate.parse(it.date).plusDays(1) } ?: return
            while (cursor.isBefore(untilExclusive) && !cursor.isAfter(throughDate)) {
                val seed = requireNotNull(previous)
                pending += seed.copy(
                    date = cursor.toString(),
                    open = seed.close,
                    close = seed.close,
                    consumed = 0f,
                    toppedUp = 0f,
                    granted = 0f,
                    avgBalance = seed.close,
                    sampleCount = 0,
                    generatedAt = generatedAt
                )
                previous = pending.last()
                if (pending.size == CLEANUP_KEY_PAGE_SIZE) flush()
                cursor = cursor.plusDays(1)
            }
        }

        while (true) {
            val page = database.historyDao().canonicalSummaryPageForSeries(
                series.accountId,
                requireIsoCurrency(series.currency),
                afterDate,
                CLEANUP_KEY_PAGE_SIZE
            ).map { it.toDomain() }
            if (page.isEmpty()) break
            page.forEach { summary ->
                val summaryDate = runCatching { java.time.LocalDate.parse(summary.date) }.getOrNull()
                    ?: return@forEach
                if (previous != null) appendGap(summaryDate)
                previous = summary
            }
            afterDate = page.last().date
            if (page.size < CLEANUP_KEY_PAGE_SIZE) break
        }
        appendGap(throughDate.plusDays(1))
        flush()
    }

    private companion object {
        const val CLEANUP_KEY_PAGE_SIZE = 200
    }
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

    override open suspend fun pageAll(
        fromInclusive: Long,
        toExclusive: Long,
        after: HistoryCursor?,
        limit: Int
    ): HistoryPage {
        require(toExclusive >= fromInclusive) { "invalid history range" }
        val rows = database.historyDao().keysetPageAll(
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
            chunk.forEach { summary ->
                database.historyDao().deleteSummaryIdentity(
                    date = summary.date,
                    accountId = summary.accountId,
                    currency = requireIsoCurrency(summary.currency),
                    identityDiscriminator = CONTINUITY_SUMMARY_IDENTITY
                )
            }
            database.historyDao().upsertSummaries(chunk.map { it.toEntity() })
            // A stale continuity transaction may have committed between the
            // first delete and this publication. Remove that harmless shadow
            // after the real row is durable; callers that already own a Room
            // transaction retain their existing atomic boundary.
            chunk.forEach { summary ->
                database.historyDao().deleteSummaryIdentity(
                    date = summary.date,
                    accountId = summary.accountId,
                    currency = requireIsoCurrency(summary.currency),
                    identityDiscriminator = CONTINUITY_SUMMARY_IDENTITY
                )
            }
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

private fun DailySummary.toEntity(identityDiscriminator: String = "") = DailySummaryEntity(
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
    generatedAt = generatedAt,
    identityDiscriminator = identityDiscriminator
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
