package com.balancesentinel.app.data.local.metadata

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
abstract class AppMetadataDao {
    @Query("SELECT * FROM app_metadata WHERE id = 0")
    abstract suspend fun get(): AppMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSingletonRow(metadata: AppMetadataEntity): Long

    suspend fun ensureSingleton(updatedAt: Long): Long =
        insertSingletonRow(AppMetadataEntity(updatedAt = updatedAt))

    @Query(
        """
        UPDATE app_metadata SET
            local_revision = local_revision + 1,
            updated_at = :updatedAt
        WHERE id = 0 AND local_revision = :expectedRevision
        """
    )
    abstract suspend fun incrementRevisionIfCurrent(expectedRevision: Long, updatedAt: Long): Int

    @Query(
        """
        UPDATE app_metadata SET
            local_revision = local_revision + 1,
            active_data_generation = :newActiveDataGeneration,
            legacy_migration_stage = :newLegacyMigrationStage,
            updated_at = :updatedAt
        WHERE id = 0
          AND local_revision = :expectedRevision
          AND active_data_generation = :expectedActiveDataGeneration
          AND legacy_migration_stage = :expectedLegacyMigrationStage
        """
    )
    abstract suspend fun advanceMetadataAndRevisionIfCurrent(
        expectedRevision: Long,
        expectedActiveDataGeneration: String,
        expectedLegacyMigrationStage: LegacyMigrationStage,
        newActiveDataGeneration: String,
        newLegacyMigrationStage: LegacyMigrationStage,
        updatedAt: Long
    ): Int
}
