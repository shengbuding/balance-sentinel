package com.balancesentinel.app.data.local.maintenance

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
abstract class MaintenanceCheckpointDao {
    @Query("SELECT * FROM maintenance_checkpoint WHERE id = 0")
    abstract suspend fun get(): MaintenanceCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSingletonRow(checkpoint: MaintenanceCheckpointEntity): Long

    suspend fun getOrCreate(zoneId: String = "UTC"): MaintenanceCheckpointEntity {
        insertSingletonRow(MaintenanceCheckpointEntity(zoneId = zoneId))
        return requireNotNull(get())
    }

    @Query(
        """
        UPDATE maintenance_checkpoint SET
            last_completed_date = :date,
            zone_id = :zoneId,
            last_success_at = :successAt
        WHERE id = 0
          AND zone_id = :expectedZoneId
          AND ((last_completed_date IS NULL AND :expectedDate IS NULL)
               OR last_completed_date = :expectedDate)
          AND ((last_success_at IS NULL AND :expectedSuccessAt IS NULL)
               OR last_success_at = :expectedSuccessAt)
          AND (
              zone_id != :zoneId
              OR last_completed_date IS NULL
              OR last_completed_date < :date
          )
        """
    )
    abstract suspend fun advanceAfterCompleteDateIfCurrent(
        date: String,
        zoneId: String,
        successAt: Long,
        expectedDate: String?,
        expectedZoneId: String,
        expectedSuccessAt: Long?
    ): Int
}
