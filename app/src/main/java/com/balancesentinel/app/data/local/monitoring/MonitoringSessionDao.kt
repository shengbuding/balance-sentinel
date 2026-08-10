package com.balancesentinel.app.data.local.monitoring

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MonitoringSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStart(session: MonitoringSessionEntity)

    @Query("SELECT * FROM monitoring_sessions WHERE id = :id")
    suspend fun get(id: String): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE ended_at IS NULL AND active_slot = 'DATA_SYNC' LIMIT 1")
    suspend fun getOpenDataSync(): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE ended_at IS NULL AND process_session_id = :processSessionId LIMIT 1")
    suspend fun getOpenForProcess(processSessionId: String): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE ended_at IS NULL")
    suspend fun listOpen(): List<MonitoringSessionEntity>

    @Query(
        """
        UPDATE monitoring_sessions SET
            ended_at = :recoveryTime,
            active_slot = NULL,
            end_reason = 'PROCESS_RECOVERY',
            recovered_at = :recoveryTime
        WHERE ended_at IS NULL AND process_session_id != :activeProcessSessionId
        """
    )
    suspend fun endOpenForRecovery(activeProcessSessionId: String, recoveryTime: Long): Int

    @Query(
        """
        UPDATE monitoring_sessions SET
            ended_at = :endedAt,
            active_slot = NULL,
            end_reason = :reason
        WHERE id = :id AND ended_at IS NULL AND active_slot = 'DATA_SYNC'
        """
    )
    suspend fun endCurrent(id: String, endedAt: Long, reason: MonitoringSessionEndReason): Int

    @Query(
        """
        SELECT * FROM monitoring_sessions
        WHERE ended_at > :cutoff AND started_at < :now
        UNION ALL
        SELECT * FROM monitoring_sessions
        WHERE ended_at IS NULL AND started_at < :now
        ORDER BY started_at, id
        """
    )
    suspend fun listOverlapping(cutoff: Long, now: Long): List<MonitoringSessionEntity>

    @Query("DELETE FROM monitoring_sessions WHERE ended_at IS NOT NULL AND ended_at <= :cutoff")
    suspend fun pruneClosedThrough(cutoff: Long): Int
}
