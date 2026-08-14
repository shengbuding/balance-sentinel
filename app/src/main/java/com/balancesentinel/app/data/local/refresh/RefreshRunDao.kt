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

    @Query("SELECT * FROM refresh_runs ORDER BY started_at DESC LIMIT :limit")
    suspend fun newestRuns(limit: Int): List<RefreshRunEntity>

    @Query("SELECT * FROM refresh_account_results WHERE run_id = :runId AND account_id = :accountId")
    suspend fun getAccountResult(runId: String, accountId: String): RefreshAccountResultEntity?

    @Query("SELECT * FROM refresh_account_results WHERE run_id = :runId ORDER BY started_at ASC")
    suspend fun getAccountResults(runId: String): List<RefreshAccountResultEntity>

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
          AND (
            :state != 'SUCCEEDED'
            OR EXISTS (
                SELECT 1 FROM accounts
                WHERE accounts.id = :accountId
                  AND accounts.revision = (
                      SELECT account_revision FROM refresh_account_results
                      WHERE refresh_account_results.run_id = :runId
                        AND refresh_account_results.account_id = :accountId
                  )
            )
          )
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
            state = (
                SELECT CASE
                    WHEN SUM(CASE WHEN state = 'SUCCEEDED' THEN 1 ELSE 0 END) = COUNT(*)
                        THEN 'SUCCEEDED'
                    WHEN SUM(CASE WHEN state = 'CANCELLED' THEN 1 ELSE 0 END) = COUNT(*)
                        THEN 'CANCELLED'
                    WHEN SUM(CASE WHEN state = 'SUCCEEDED' THEN 1 ELSE 0 END) > 0
                        THEN 'PARTIAL'
                    ELSE 'FAILED'
                END
                FROM refresh_account_results WHERE run_id = :runId
            ),
            completed_at = :completedAt,
            account_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId),
            success_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId AND state = 'SUCCEEDED'),
            failure_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId AND state NOT IN ('RUNNING', 'SUCCEEDED', 'CANCELLED')),
            cancelled_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = :runId AND state = 'CANCELLED')
        WHERE id = :runId
          AND state = 'RUNNING'
          AND NOT EXISTS (
              SELECT 1 FROM refresh_account_results
              WHERE run_id = :runId AND state = 'RUNNING'
          )
        """
    )
    suspend fun deriveAndUpdateAggregate(runId: String, completedAt: Long): Int

    @Query(
        """
        UPDATE refresh_account_results SET
            state = 'CANCELLED',
            error_category = 'CANCELLED',
            error_code = 'CANCELLED',
            retryable = 0,
            retry_after_at = NULL,
            completed_at = :completedAt
        WHERE run_id = :runId AND state = 'RUNNING'
        """
    )
    suspend fun cancelRunningResults(runId: String, completedAt: Long): Int

    @Query(
        """
        UPDATE refresh_runs SET state = 'INTERRUPTED', completed_at = :completedAt
        WHERE state = 'RUNNING'
          AND (owner_process_session_id IS NULL OR owner_process_session_id != :activeOwner)
        """
    )
    suspend fun interruptRunsWithoutOwner(activeOwner: String, completedAt: Long): Int

    @Query(
        """
        UPDATE refresh_account_results SET
            state = 'INTERRUPTED',
            error_category = 'INTERRUPTED',
            error_code = 'PROCESS_RESTARTED',
            retryable = 0,
            retry_after_at = NULL,
            completed_at = :completedAt
        WHERE state = 'RUNNING'
          AND run_id IN (
              SELECT id FROM refresh_runs
              WHERE state = 'INTERRUPTED' AND completed_at = :completedAt
          )
        """
    )
    suspend fun interruptRunningResults(completedAt: Long): Int

    @Query(
        """
        UPDATE refresh_runs SET
            account_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = refresh_runs.id),
            success_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = refresh_runs.id AND state = 'SUCCEEDED'),
            failure_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = refresh_runs.id AND state NOT IN ('RUNNING', 'SUCCEEDED', 'CANCELLED')),
            cancelled_count = (SELECT COUNT(*) FROM refresh_account_results WHERE run_id = refresh_runs.id AND state = 'CANCELLED')
        WHERE state = 'INTERRUPTED' AND completed_at = :completedAt
        """
    )
    suspend fun updateInterruptedCounts(completedAt: Long): Int
}
