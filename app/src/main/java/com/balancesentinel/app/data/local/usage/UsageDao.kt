package com.balancesentinel.app.data.local.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface UsageDao {
    companion object {
        const val RECORD_BATCH_SIZE = 500
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: UsageSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecords(records: List<UsageRecordEntity>)

    @Query("DELETE FROM usage_records WHERE snapshot_id = :snapshotId")
    suspend fun deleteRecords(snapshotId: String)

    @Transaction
    suspend fun upsertSnapshotWithRecords(
        snapshot: UsageSnapshotEntity,
        records: List<UsageRecordEntity>
    ) {
        upsertSnapshot(snapshot)
        deleteRecords(snapshot.id)
        records.chunked(RECORD_BATCH_SIZE).forEach { batch ->
            upsertRecords(batch)
        }
    }

    @Query(
        """
        SELECT * FROM usage_snapshots
        WHERE account_id = :accountId AND captured_at >= :fromInclusive
          AND captured_at < :toExclusive
          AND (
            :afterCapturedAt IS NULL
            OR captured_at > :afterCapturedAt
            OR (captured_at = :afterCapturedAt AND id > :afterId)
          )
        ORDER BY captured_at, id LIMIT :limit
        """
    )
    suspend fun rangePage(
        accountId: String,
        fromInclusive: Long,
        limit: Int,
        toExclusive: Long = Long.MAX_VALUE,
        afterCapturedAt: Long? = null,
        afterId: String? = null
    ): List<UsageSnapshotEntity>

    @Query(
        """
        SELECT * FROM usage_snapshots
        WHERE account_id = :accountId
          AND captured_at >= :fromInclusive AND captured_at < :toExclusive
          AND (
            :afterCapturedAt IS NULL
            OR captured_at > :afterCapturedAt
            OR (captured_at = :afterCapturedAt AND id > :afterId)
          )
        ORDER BY captured_at, id LIMIT :limit
        """
    )
    suspend fun keysetPage(
        accountId: String,
        fromInclusive: Long,
        toExclusive: Long,
        afterCapturedAt: Long?,
        afterId: String?,
        limit: Int
    ): List<UsageSnapshotEntity>

    @Query(
        """
        SELECT * FROM usage_snapshots
        WHERE account_id = :accountId
          AND captured_at >= :fromInclusive AND captured_at < :toExclusive
        ORDER BY captured_at, id
        """
    )
    suspend fun range(
        accountId: String,
        fromInclusive: Long,
        toExclusive: Long
    ): List<UsageSnapshotEntity>

    @Query(
        """
        SELECT COUNT(*) FROM usage_snapshots
        WHERE account_id = :accountId
          AND captured_at >= :fromInclusive AND captured_at < :toExclusive
        """
    )
    suspend fun countRange(accountId: String, fromInclusive: Long, toExclusive: Long): Long

    @Query("SELECT * FROM usage_records WHERE snapshot_id = :snapshotId ORDER BY record_ordinal")
    suspend fun getRecords(snapshotId: String): List<UsageRecordEntity>

    @Query("SELECT COUNT(*) FROM usage_snapshots")
    suspend fun countSnapshots(): Long


    @Query("DELETE FROM usage_snapshots WHERE account_id = :accountId")
    suspend fun deleteByAccount(accountId: String): Int
}
