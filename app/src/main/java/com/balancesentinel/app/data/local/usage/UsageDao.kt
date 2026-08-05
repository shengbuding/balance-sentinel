package com.balancesentinel.app.data.local.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface UsageDao {
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
        if (records.isNotEmpty()) upsertRecords(records)
    }

    @Query(
        """
        SELECT * FROM usage_snapshots
        WHERE account_id = :accountId AND captured_at >= :fromInclusive
        ORDER BY captured_at, id LIMIT :limit
        """
    )
    suspend fun rangePage(
        accountId: String,
        fromInclusive: Long,
        limit: Int
    ): List<UsageSnapshotEntity>

    @Query("SELECT * FROM usage_records WHERE snapshot_id = :snapshotId ORDER BY record_ordinal")
    suspend fun getRecords(snapshotId: String): List<UsageRecordEntity>

    @Query("SELECT COUNT(*) FROM usage_snapshots")
    suspend fun countSnapshots(): Long

    @Query("DELETE FROM usage_snapshots WHERE account_id = :accountId")
    suspend fun deleteByAccount(accountId: String): Int
}
