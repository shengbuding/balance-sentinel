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

    @Query("DELETE FROM event_logs WHERE recorded_at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("SELECT * FROM event_logs WHERE id = :id")
    suspend fun get(id: Long): EventLogEntity?
}
