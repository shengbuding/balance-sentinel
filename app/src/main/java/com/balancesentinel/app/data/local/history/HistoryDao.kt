package com.balancesentinel.app.data.local.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.ColumnInfo

data class BalanceAggregate(
    @ColumnInfo(name = "row_count") val count: Long,
    @ColumnInfo(name = "total_balance_sum") val totalBalanceSum: Double?,
    @ColumnInfo(name = "granted_balance_sum") val grantedBalanceSum: Double?,
    @ColumnInfo(name = "topped_up_balance_sum") val toppedUpBalanceSum: Double?
)

data class HistoryAggregateProjection(
    @ColumnInfo(name = "row_count") val count: Long,
    @ColumnInfo(name = "open_balance") val openBalance: Double?,
    @ColumnInfo(name = "close_balance") val closeBalance: Double?,
    @ColumnInfo(name = "consumed_balance") val consumedBalance: Double?,
    @ColumnInfo(name = "topped_up_balance") val toppedUpBalance: Double?,
    @ColumnInfo(name = "granted_balance") val grantedBalance: Double?,
    @ColumnInfo(name = "average_balance") val averageBalance: Double?,
    @ColumnInfo(name = "topped_up_balance_close") val toppedUpBalanceClose: Double?,
    @ColumnInfo(name = "granted_balance_close") val grantedBalanceClose: Double?
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBalanceBatch(records: List<BalanceRecordEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaries(summaries: List<DailySummaryEntity>)

    @Query(
        """
        SELECT * FROM balance_records
        WHERE account_id = :accountId AND currency = :currency
          AND recorded_at >= :fromInclusive AND recorded_at < :toExclusive
          AND (
            :afterRecordedAt IS NULL
            OR recorded_at < :afterRecordedAt
            OR (recorded_at = :afterRecordedAt AND id < :afterId)
          )
        ORDER BY recorded_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun rangePage(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
        afterRecordedAt: Long? = null,
        afterId: Long? = null
    ): List<BalanceRecordEntity>

    @Query(
        """
        SELECT * FROM balance_records
        WHERE account_id = :accountId AND currency = :currency
          AND recorded_at >= :fromInclusive AND recorded_at < :toExclusive
          AND (
            :afterRecordedAt IS NULL
            OR recorded_at < :afterRecordedAt
            OR (recorded_at = :afterRecordedAt AND id < :afterId)
          )
        ORDER BY recorded_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun keysetPage(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        afterRecordedAt: Long?,
        afterId: Long?,
        limit: Int
    ): List<BalanceRecordEntity>

    @Query(
        """
        SELECT * FROM balance_records
        WHERE recorded_at >= :fromInclusive AND recorded_at < :toExclusive
          AND (
            :afterRecordedAt IS NULL
            OR recorded_at < :afterRecordedAt
            OR (recorded_at = :afterRecordedAt AND id < :afterId)
          )
        ORDER BY recorded_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun keysetPageAll(
        fromInclusive: Long,
        toExclusive: Long,
        afterRecordedAt: Long?,
        afterId: Long?,
        limit: Int
    ): List<BalanceRecordEntity>

    @Query(
        """
        SELECT * FROM balance_records
        WHERE account_id = :accountId AND currency = :currency
          AND recorded_at >= :fromInclusive AND recorded_at < :toExclusive
        ORDER BY recorded_at DESC, id DESC
        """
    )
    suspend fun range(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): List<BalanceRecordEntity>

    @Query(
        """
        SELECT COUNT(*) AS row_count,
            SUM(total_balance) AS total_balance_sum,
            SUM(granted_balance) AS granted_balance_sum,
            SUM(topped_up_balance) AS topped_up_balance_sum
        FROM balance_records
        WHERE account_id = :accountId AND currency = :currency
          AND recorded_at >= :fromInclusive AND recorded_at < :toExclusive
        """
    )
    suspend fun aggregateRange(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): BalanceAggregate

    @Query(
        """
        WITH paired AS (
            SELECT
                current_record.total_balance,
                current_record.granted_balance,
                current_record.topped_up_balance,
                previous_record.id AS previous_id,
                previous_record.total_balance AS previous_total,
                previous_record.granted_balance AS previous_granted,
                previous_record.topped_up_balance AS previous_topped_up,
                next_record.id AS next_id,
                current_record.recorded_at,
                current_record.id
            FROM balance_records AS current_record
            LEFT JOIN balance_records AS previous_record ON previous_record.id = (
                SELECT candidate.id FROM balance_records AS candidate
                WHERE candidate.account_id = :accountId AND candidate.currency = :currency
                  AND candidate.recorded_at >= :fromInclusive AND candidate.recorded_at < :toExclusive
                  AND (candidate.recorded_at < current_record.recorded_at OR
                      (candidate.recorded_at = current_record.recorded_at AND candidate.id < current_record.id))
                ORDER BY candidate.recorded_at DESC, candidate.id DESC LIMIT 1
            )
            LEFT JOIN balance_records AS next_record ON next_record.id = (
                SELECT candidate.id FROM balance_records AS candidate
                WHERE candidate.account_id = :accountId AND candidate.currency = :currency
                  AND candidate.recorded_at >= :fromInclusive AND candidate.recorded_at < :toExclusive
                  AND (candidate.recorded_at > current_record.recorded_at OR
                      (candidate.recorded_at = current_record.recorded_at AND candidate.id > current_record.id))
                ORDER BY candidate.recorded_at, candidate.id LIMIT 1
            )
            WHERE current_record.account_id = :accountId AND current_record.currency = :currency
              AND current_record.recorded_at >= :fromInclusive AND current_record.recorded_at < :toExclusive
        ), deltas AS (
            SELECT *,
                topped_up_balance - previous_topped_up AS topped_up_delta,
                granted_balance - previous_granted AS granted_delta,
                total_balance - previous_total AS balance_delta
            FROM paired
        )
        SELECT
            COUNT(*) AS row_count,
            MAX(CASE WHEN previous_id IS NULL THEN total_balance END) AS open_balance,
            MAX(CASE WHEN next_id IS NULL THEN total_balance END) AS close_balance,
            SUM(CASE
                WHEN previous_id IS NULL THEN 0.0
                WHEN ((CASE WHEN topped_up_delta >= 1.0 AND
                        (topped_up_delta - CAST(topped_up_delta AS INTEGER) < 0.01 OR
                         topped_up_delta - CAST(topped_up_delta AS INTEGER) > 0.99)
                        THEN topped_up_delta ELSE 0.0 END) +
                    (CASE WHEN granted_delta > 0.0 THEN granted_delta ELSE 0.0 END) -
                    balance_delta) > 0.0
                    THEN (CASE WHEN topped_up_delta >= 1.0 AND
                        (topped_up_delta - CAST(topped_up_delta AS INTEGER) < 0.01 OR
                         topped_up_delta - CAST(topped_up_delta AS INTEGER) > 0.99)
                        THEN topped_up_delta ELSE 0.0 END) +
                        (CASE WHEN granted_delta > 0.0 THEN granted_delta ELSE 0.0 END) -
                        balance_delta
                ELSE 0.0
            END) AS consumed_balance,
            SUM(CASE
                WHEN previous_id IS NULL THEN 0.0
                WHEN topped_up_delta >= 1.0 AND
                    (topped_up_delta - CAST(topped_up_delta AS INTEGER) < 0.01 OR
                     topped_up_delta - CAST(topped_up_delta AS INTEGER) > 0.99)
                    THEN topped_up_delta ELSE 0.0 END
            ) AS topped_up_balance,
            SUM(CASE
                WHEN previous_id IS NULL THEN 0.0
                WHEN granted_delta > 0.0 THEN granted_delta ELSE 0.0 END
            ) AS granted_balance,
            AVG(total_balance) AS average_balance,
            MAX(CASE WHEN next_id IS NULL THEN topped_up_balance END) AS topped_up_balance_close,
            MAX(CASE WHEN next_id IS NULL THEN granted_balance END) AS granted_balance_close
        FROM deltas
        """
    )
    suspend fun aggregateSemantic(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): HistoryAggregateProjection

    @Query(
        """
        SELECT COUNT(*) FROM balance_records
        WHERE account_id = :accountId AND currency = :currency
          AND recorded_at >= :fromInclusive AND recorded_at < :toExclusive
        """
    )
    suspend fun countRange(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): Long

    @Query("SELECT COUNT(*) FROM balance_records")
    suspend fun countRecords(): Long

    @Query("SELECT COUNT(*) FROM daily_summaries")
    suspend fun countSummaries(): Long

    @Query("SELECT DISTINCT currency FROM balance_records ORDER BY currency")
    suspend fun distinctCurrencies(): List<String>

    @Query("DELETE FROM balance_records WHERE recorded_at >= :fromInclusive AND recorded_at < :toExclusive")
    suspend fun deleteRawForDate(fromInclusive: Long, toExclusive: Long): Int

    @Query("SELECT * FROM daily_summaries WHERE date = :date AND account_id = :accountId AND currency = :currency")
    suspend fun getSummary(date: String, accountId: String, currency: String): DailySummaryEntity?

    @Query(
        """
        SELECT * FROM daily_summaries
        WHERE (:accountId IS NULL OR account_id = :accountId)
          AND (:currency IS NULL OR currency = :currency)
          AND (:fromDateInclusive IS NULL OR date >= :fromDateInclusive)
          AND (:toDateInclusive IS NULL OR date <= :toDateInclusive)
        ORDER BY date, account_id, currency
        """
    )
    suspend fun querySummaries(
        accountId: String?,
        currency: String?,
        fromDateInclusive: String?,
        toDateInclusive: String?
    ): List<DailySummaryEntity>
}
