package com.balancesentinel.app.data.local.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
        ORDER BY recorded_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun rangePage(
        accountId: String,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int
    ): List<BalanceRecordEntity>

    @Query("SELECT COUNT(*) FROM balance_records")
    suspend fun countRecords(): Long

    @Query("SELECT DISTINCT currency FROM balance_records ORDER BY currency")
    suspend fun distinctCurrencies(): List<String>

    @Query("DELETE FROM balance_records WHERE recorded_at >= :fromInclusive AND recorded_at < :toExclusive")
    suspend fun deleteRawForDate(fromInclusive: Long, toExclusive: Long): Int

    @Query("SELECT * FROM daily_summaries WHERE date = :date AND account_id = :accountId AND currency = :currency")
    suspend fun getSummary(date: String, accountId: String, currency: String): DailySummaryEntity?
}
