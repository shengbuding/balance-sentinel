package com.balancesentinel.app.data.local.mutation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MutationOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrepared(operation: MutationOperationEntity)

    @Query("SELECT * FROM mutation_operations WHERE id = :id")
    suspend fun get(id: String): MutationOperationEntity?

    @Query(
        """
        SELECT * FROM mutation_operations
        WHERE stage NOT IN ('COMPLETED', 'FAILED')
        ORDER BY updated_at, id
        """
    )
    suspend fun listRecoverable(): List<MutationOperationEntity>

    @Query(
        """
        UPDATE mutation_operations SET
            stage = :stage,
            batch_cursor = :batchCursor,
            error_code = :errorCode,
            error_message = :errorMessage,
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateStage(
        id: String,
        stage: MutationStage,
        batchCursor: Long,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE mutation_operations SET
            stage = 'PUBLISHED', published_at = :publishedAt, updated_at = :publishedAt
        WHERE id = :id AND stage NOT IN ('PUBLISHED', 'COMPLETED', 'FAILED')
        """
    )
    suspend fun markPublished(id: String, publishedAt: Long): Int

    @Query(
        """
        UPDATE mutation_operations SET
            stage = 'COMPLETED', completed_at = :completedAt, updated_at = :completedAt
        WHERE id = :id AND stage = 'PUBLISHED'
        """
    )
    suspend fun markCompleted(id: String, completedAt: Long): Int
}
