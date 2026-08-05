package com.balancesentinel.app.data.local.monitoring

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MonitoringSessionEndReason {
    USER_STOPPED,
    SERVICE_DESTROYED,
    PLATFORM_TIMEOUT,
    PROCESS_RECOVERY,
    PLATFORM_LIMITED,
    PAUSED;

    companion object {
        fun fromStorage(value: String): MonitoringSessionEndReason = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown MonitoringSessionEndReason: $value")
    }
}

@Entity(
    tableName = "monitoring_sessions",
    indices = [
        Index(value = ["ended_at", "started_at"]),
        Index(value = ["process_session_id", "ended_at"]),
        Index(value = ["active_slot"], unique = true)
    ]
)
data class MonitoringSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "process_session_id")
    val processSessionId: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at", defaultValue = "NULL")
    val endedAt: Long? = null,
    @ColumnInfo(name = "active_slot", defaultValue = "NULL")
    val activeSlot: String? = null,
    @ColumnInfo(name = "end_reason", defaultValue = "NULL")
    val endReason: MonitoringSessionEndReason? = null,
    @ColumnInfo(name = "recovered_at", defaultValue = "NULL")
    val recoveredAt: Long? = null
) {
    companion object {
        const val DATA_SYNC_SLOT = "DATA_SYNC"
    }
}
