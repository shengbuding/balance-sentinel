package com.balancesentinel.app.data.local.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<EventLogEntity>): List<Long>

    @Query("SELECT * FROM event_logs ORDER BY recorded_at DESC, id DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<EventLogEntity>

    @Query(
        """
        SELECT * FROM event_logs
        WHERE (:afterRecordedAt IS NULL OR recorded_at < :afterRecordedAt
            OR (recorded_at = :afterRecordedAt AND id < :afterId))
        ORDER BY recorded_at DESC, id DESC LIMIT :limit
        """
    )
    suspend fun newestPage(
        afterRecordedAt: Long?,
        afterId: Long?,
        limit: Int
    ): List<EventLogEntity>

    @Query("DELETE FROM event_logs WHERE recorded_at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("SELECT * FROM event_logs WHERE id = :id")
    suspend fun get(id: Long): EventLogEntity?
}
