package com.balancesentinel.app.data.local.update

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadState {
    QUEUED,
    RUNNING,
    CANCELLING,
    CANCELLED,
    FAILED,
    COMPLETED;

    companion object {
        fun fromStorage(value: String): DownloadState = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown DownloadState: $value")
    }
}

@Entity(
    tableName = "download_operations",
    indices = [
        Index(value = ["active_tag"], unique = true),
        Index(value = ["active_target_path"], unique = true)
    ]
)
data class DownloadOperationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    val tag: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String,
    @ColumnInfo(name = "temporary_path")
    val temporaryPath: String,
    @ColumnInfo(name = "target_path")
    val targetPath: String,
    @ColumnInfo(defaultValue = "'QUEUED'")
    val state: DownloadState = DownloadState.QUEUED,
    @ColumnInfo(name = "downloaded_bytes", defaultValue = "0")
    val downloadedBytes: Long = 0,
    @ColumnInfo(name = "total_bytes", defaultValue = "NULL")
    val totalBytes: Long? = null,
    @ColumnInfo(name = "error_code", defaultValue = "NULL")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message", defaultValue = "NULL")
    val errorMessage: String? = null,
    @ColumnInfo(name = "active_tag", defaultValue = "NULL")
    val activeTag: String? = null,
    @ColumnInfo(name = "active_target_path", defaultValue = "NULL")
    val activeTargetPath: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at", defaultValue = "NULL")
    val completedAt: Long? = null
)
