package com.balancesentinel.app.data.local.refresh

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class RefreshAccountResultState {
    RUNNING,
    SUCCEEDED,
    AUTHENTICATION_FAILED,
    NETWORK_FAILED,
    RATE_LIMITED,
    RESPONSE_INVALID,
    SCRIPT_POLICY_DENIED,
    SCRIPT_TIMEOUT,
    ACCOUNT_STALE,
    PERSISTENCE_FAILED,
    CANCELLED,
    INTERRUPTED,
    SKIPPED;

    companion object {
        fun fromStorage(value: String): RefreshAccountResultState = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown RefreshAccountResultState: $value")
    }
}

enum class RefreshErrorCategory {
    AUTHENTICATION,
    NETWORK,
    RATE_LIMIT,
    RESPONSE,
    SCRIPT_POLICY,
    SCRIPT_TIMEOUT,
    ACCOUNT_STALE,
    PERSISTENCE,
    CANCELLED,
    INTERRUPTED,
    UNKNOWN;

    companion object {
        fun fromStorage(value: String): RefreshErrorCategory = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown RefreshErrorCategory: $value")
    }
}

@Entity(
    tableName = "refresh_account_results",
    primaryKeys = ["run_id", "account_id"],
    foreignKeys = [
        ForeignKey(
            entity = RefreshRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["account_id", "completed_at"]),
        Index(value = ["run_id", "state"])
    ]
)
data class RefreshAccountResultEntity(
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "account_revision")
    val accountRevision: Long,
    @ColumnInfo(defaultValue = "'RUNNING'")
    val state: RefreshAccountResultState = RefreshAccountResultState.RUNNING,
    @ColumnInfo(name = "error_category", defaultValue = "NULL")
    val errorCategory: RefreshErrorCategory? = null,
    @ColumnInfo(name = "error_code", defaultValue = "NULL")
    val errorCode: String? = null,
    @ColumnInfo(defaultValue = "0")
    val retryable: Boolean = false,
    @ColumnInfo(name = "retry_after_at", defaultValue = "NULL")
    val retryAfterAt: Long? = null,
    @ColumnInfo(name = "data_timestamp", defaultValue = "NULL")
    val dataTimestamp: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val stale: Boolean = false,
    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "completed_at", defaultValue = "NULL")
    val completedAt: Long? = null
)
