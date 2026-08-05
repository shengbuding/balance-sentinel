package com.balancesentinel.app.data.local.monitoring

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class MonitoringObservedState {
    STOPPED,
    STARTING,
    RUNNING,
    ABNORMAL,
    PLATFORM_LIMITED,
    PAUSED;

    companion object {
        fun fromStorage(value: String): MonitoringObservedState = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown MonitoringObservedState: $value")
    }
}

@Entity(
    tableName = "monitoring_state",
    foreignKeys = [
        ForeignKey(
            entity = MonitoringSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["current_monitoring_session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class MonitoringStateEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "0")
    val id: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val desired: Boolean = false,
    @ColumnInfo(name = "observed_state", defaultValue = "'STOPPED'")
    val observedState: MonitoringObservedState = MonitoringObservedState.STOPPED,
    @ColumnInfo(name = "process_session_id", defaultValue = "NULL")
    val processSessionId: String? = null,
    @ColumnInfo(name = "lease_expires_at", defaultValue = "NULL")
    val leaseExpiresAt: Long? = null,
    @ColumnInfo(name = "current_monitoring_session_id", defaultValue = "NULL")
    val currentMonitoringSessionId: String? = null,
    @ColumnInfo(name = "foreground_session_started_at", defaultValue = "NULL")
    val foregroundSessionStartedAt: Long? = null,
    @ColumnInfo(name = "foreground_session_ended_at", defaultValue = "NULL")
    val foregroundSessionEndedAt: Long? = null,
    @ColumnInfo(name = "last_user_foreground_reset_at", defaultValue = "NULL")
    val lastUserForegroundResetAt: Long? = null,
    @ColumnInfo(name = "state_reason", defaultValue = "NULL")
    val stateReason: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
