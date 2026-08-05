package com.balancesentinel.app.data.local.refresh

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RefreshRunSource {
    MANUAL,
    BACKGROUND,
    FOREGROUND,
    WIDGET;

    companion object {
        fun fromStorage(value: String): RefreshRunSource = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown RefreshRunSource: $value")
    }
}

enum class RefreshRunState {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    companion object {
        fun fromStorage(value: String): RefreshRunState = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown RefreshRunState: $value")
    }
}

@Entity(
    tableName = "refresh_runs",
    indices = [
        Index(value = ["state", "started_at"]),
        Index(value = ["owner_process_session_id", "state"])
    ]
)
data class RefreshRunEntity(
    @PrimaryKey
    val id: String,
    val source: RefreshRunSource,
    @ColumnInfo(name = "owner_process_session_id", defaultValue = "NULL")
    val ownerProcessSessionId: String? = null,
    @ColumnInfo(defaultValue = "'RUNNING'")
    val state: RefreshRunState = RefreshRunState.RUNNING,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "completed_at", defaultValue = "NULL")
    val completedAt: Long? = null,
    @ColumnInfo(name = "account_count", defaultValue = "0")
    val accountCount: Int = 0,
    @ColumnInfo(name = "success_count", defaultValue = "0")
    val successCount: Int = 0,
    @ColumnInfo(name = "failure_count", defaultValue = "0")
    val failureCount: Int = 0,
    @ColumnInfo(name = "cancelled_count", defaultValue = "0")
    val cancelledCount: Int = 0,
    @ColumnInfo(name = "error_code", defaultValue = "NULL")
    val errorCode: String? = null
)
