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

    @Query("SELECT DISTINCT currency FROM balance_records ORDER BY currency")
    suspend fun distinctCurrencies(): List<String>

    @Query("DELETE FROM balance_records WHERE recorded_at >= :fromInclusive AND recorded_at < :toExclusive")
    suspend fun deleteRawForDate(fromInclusive: Long, toExclusive: Long): Int

    @Query("SELECT * FROM daily_summaries WHERE date = :date AND account_id = :accountId AND currency = :currency")
    suspend fun getSummary(date: String, accountId: String, currency: String): DailySummaryEntity?
}
