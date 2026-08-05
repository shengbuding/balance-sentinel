package com.balancesentinel.app.data.local.maintenance

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MaintenanceCheckpointDao {
    @Query("SELECT * FROM maintenance_checkpoint WHERE id = 0")
    suspend fun get(): MaintenanceCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(checkpoint: MaintenanceCheckpointEntity): Long

    suspend fun getOrCreate(defaultValue: MaintenanceCheckpointEntity): MaintenanceCheckpointEntity {
        insertIfMissing(defaultValue)
        return requireNotNull(get())
    }

    @Query(
        """
        UPDATE maintenance_checkpoint SET
            last_completed_date = :date,
            zone_id = :zoneId,
            last_success_at = :successAt
        WHERE id = 0
        """
    )
    suspend fun advanceAfterCompleteDate(date: String, zoneId: String, successAt: Long): Int
}
