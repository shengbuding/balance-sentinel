package com.balancesentinel.app.data.local.refresh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RefreshRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RefreshRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRunningResult(result: RefreshAccountResultEntity)

    @Query("SELECT * FROM refresh_runs WHERE id = :id")
    suspend fun getRun(id: String): RefreshRunEntity?

    @Query("SELECT * FROM refresh_account_results WHERE run_id = :runId AND account_id = :accountId")
    suspend fun getAccountResult(runId: String, accountId: String): RefreshAccountResultEntity?

    @Query(
        """
        UPDATE refresh_account_results SET
            state = :state,
            error_category = :errorCategory,
            error_code = :errorCode,
            retryable = :retryable,
            retry_after_at = :retryAfterAt,
            data_timestamp = :dataTimestamp,
            stale = :stale,
            attempt_count = :attemptCount,
            completed_at = :completedAt
        WHERE run_id = :runId AND account_id = :accountId AND state = 'RUNNING'
        """
    )
    suspend fun completeAccountAtomically(
        runId: String,
        accountId: String,
        state: RefreshAccountResultState,
        errorCategory: RefreshErrorCategory?,
        errorCode: String?,
        retryable: Boolean,
        retryAfterAt: Long?,
        dataTimestamp: Long?,
        stale: Boolean,
        attemptCount: Int,
        completedAt: Long
    ): Int

    @Query(
        """
        UPDATE refresh_runs SET
            state = :state,
            completed_at = :completedAt,
            account_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId),
            success_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId AND state = 'SUCCEEDED'),
            failure_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId AND state NOT IN ('RUNNING', 'SUCCEEDED', 'CANCELLED')),
            cancelled_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId AND state = 'CANCELLED')
        WHERE id = :runId
        """
    )
    suspend fun deriveAndUpdateAggregate(
        runId: String,
        state: RefreshRunState,
        completedAt: Long
    ): Int

    @Query(
        """
        UPDATE refresh_runs SET state = 'INTERRUPTED', completed_at = :completedAt
        WHERE state = 'RUNNING'
          AND (owner_process_session_id IS NULL OR owner_process_session_id != :activeOwner)
        """
    )
    suspend fun interruptRunsWithoutOwner(activeOwner: String, completedAt: Long): Int
}
