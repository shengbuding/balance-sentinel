package com.balancesentinel.app.data.local.monitoring

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MonitoringStateDao {
    @Query("SELECT * FROM monitoring_state WHERE id = 0")
    abstract suspend fun get(): MonitoringStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSingletonRow(state: MonitoringStateEntity): Long

    suspend fun getOrCreate(updatedAt: Long): MonitoringStateEntity {
        insertSingletonRow(MonitoringStateEntity(updatedAt = updatedAt))
        return requireNotNull(get())
    }

    @Query("SELECT * FROM monitoring_state WHERE id = 0")
    abstract fun observe(): Flow<MonitoringStateEntity?>

    @Query("UPDATE monitoring_state SET desired = :desired, updated_at = :updatedAt WHERE id = 0")
    abstract suspend fun setDesired(desired: Boolean, updatedAt: Long): Int

    @Query(
        """
        UPDATE monitoring_state SET
            desired = :desired,
            observed_state = :observedState,
            state_reason = :reason,
            updated_at = :updatedAt
        WHERE id = 0
        """
    )
    abstract suspend fun setDesiredAndState(
        desired: Boolean,
        observedState: MonitoringObservedState,
        reason: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE monitoring_state SET
            process_session_id = :processSessionId,
            lease_expires_at = :leaseExpiresAt,
            observed_state = 'RUNNING',
            updated_at = :updatedAt
        WHERE id = 0
        """
    )
    abstract suspend fun renewLease(
        processSessionId: String,
        leaseExpiresAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE monitoring_state SET
            process_session_id = :processSessionId,
            lease_expires_at = :leaseExpiresAt,
            observed_state = 'RUNNING',
            updated_at = :updatedAt
        WHERE id = 0 AND desired = 1
        """
    )
    abstract suspend fun renewDesiredLease(
        processSessionId: String,
        leaseExpiresAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE monitoring_state SET
            current_monitoring_session_id = :monitoringSessionId,
            process_session_id = :processSessionId,
            foreground_session_started_at = :startedAt,
            foreground_session_ended_at = NULL,
            observed_state = 'RUNNING',
            updated_at = :startedAt
        WHERE id = 0
        """
    )
    abstract suspend fun projectSessionStart(
        monitoringSessionId: String,
        processSessionId: String,
        startedAt: Long
    ): Int

    @Query(
        """
        UPDATE monitoring_state SET
            current_monitoring_session_id = NULL,
            foreground_session_ended_at = :endedAt,
            lease_expires_at = NULL,
            observed_state = :observedState,
            state_reason = :reason,
            updated_at = :endedAt
        WHERE id = 0 AND current_monitoring_session_id = :monitoringSessionId
        """
    )
    abstract suspend fun projectSessionEnd(
        monitoringSessionId: String,
        endedAt: Long,
        observedState: MonitoringObservedState,
        reason: String?
    ): Int
}
