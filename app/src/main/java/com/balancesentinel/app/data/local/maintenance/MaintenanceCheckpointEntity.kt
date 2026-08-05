package com.balancesentinel.app.data.local.maintenance

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "maintenance_checkpoint")
data class MaintenanceCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "0")
    val id: Int = 0,
    @ColumnInfo(name = "last_completed_date", defaultValue = "NULL")
    val lastCompletedDate: String? = null,
    @ColumnInfo(name = "zone_id", defaultValue = "'UTC'")
    val zoneId: String = "UTC",
    @ColumnInfo(name = "last_success_at", defaultValue = "NULL")
    val lastSuccessAt: Long? = null
)
