package com.balancesentinel.app.data.local.metadata

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppMetadataDao {
    @Query("SELECT * FROM app_metadata WHERE id = 0")
    suspend fun get(): AppMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureSingleton(metadata: AppMetadataEntity): Long

    @Query(
        """
        UPDATE app_metadata SET
            local_revision = local_revision + 1,
            updated_at = :updatedAt
        WHERE id = 0 AND local_revision = :expectedRevision
        """
    )
    suspend fun incrementRevisionIfCurrent(expectedRevision: Long, updatedAt: Long): Int

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
    suspend fun advanceMetadataAndRevisionIfCurrent(
        expectedRevision: Long,
        expectedActiveDataGeneration: String,
        expectedLegacyMigrationStage: LegacyMigrationStage,
        newActiveDataGeneration: String,
        newLegacyMigrationStage: LegacyMigrationStage,
        updatedAt: Long
    ): Int
}
