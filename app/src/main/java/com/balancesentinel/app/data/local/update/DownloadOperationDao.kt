package com.balancesentinel.app.data.local.update

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActive(operation: DownloadOperationEntity)

    @Query("SELECT * FROM download_operations WHERE id = :id")
    suspend fun get(id: String): DownloadOperationEntity?

    @Query("SELECT * FROM download_operations WHERE id = :id")
    fun observe(id: String): Flow<DownloadOperationEntity?>

    @Query("SELECT * FROM download_operations WHERE tag = :tag ORDER BY created_at DESC, id DESC LIMIT 1")
    fun observeLatestForTag(tag: String): Flow<DownloadOperationEntity?>

    @Query("SELECT * FROM download_operations WHERE tag = :tag ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun getLatestForTag(tag: String): DownloadOperationEntity?

    @Query("SELECT * FROM download_operations WHERE active_tag = :tag LIMIT 1")
    suspend fun getActiveForTag(tag: String): DownloadOperationEntity?

    @Query(
        "SELECT COUNT(*) FROM download_operations " +
            "WHERE id = :id AND owner_id = :ownerId AND state = 'RUNNING' " +
            "AND active_tag IS NOT NULL AND active_target_path IS NOT NULL"
    )
    suspend fun isActiveOwner(id: String, ownerId: String): Int

    @Query(
        """
        UPDATE download_operations SET
            state = :state,
            downloaded_bytes = :downloadedBytes,
            total_bytes = :totalBytes,
            error_code = :errorCode,
            error_message = :errorMessage,
            active_tag = :activeTag,
            active_target_path = :activeTargetPath,
            updated_at = :updatedAt,
            completed_at = :completedAt
        WHERE id = :id AND owner_id = :ownerId
            AND state IN (:expectedStates)
            AND active_tag IS NOT NULL AND active_target_path IS NOT NULL
        """
    )
    suspend fun transitionOwnedOperation(
        id: String,
        ownerId: String,
        expectedStates: List<DownloadState>,
        state: DownloadState,
        downloadedBytes: Long,
        totalBytes: Long?,
        errorCode: String?,
        errorMessage: String?,
        activeTag: String?,
        activeTargetPath: String?,
        updatedAt: Long,
        completedAt: Long?
    ): Int

    @Query("SELECT * FROM download_operations WHERE active_tag IS NOT NULL OR active_target_path IS NOT NULL ORDER BY created_at")
    suspend fun listActive(): List<DownloadOperationEntity>
}
