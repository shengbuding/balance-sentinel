package com.balancesentinel.app.data.local.mutation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MutationOperationType {
    ACCOUNT_REPLACE,
    ACCOUNT_DELETE,
    CONFIG_IMPORT,
    LEGACY_ACCOUNT_MIGRATION,
    LEGACY_DATA_MIGRATION,
    HISTORY_DATA_IMPORT;

    companion object {
        fun fromStorage(value: String): MutationOperationType = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown MutationOperationType: $value")
    }
}

enum class MutationStage {
    PREPARED,
    CREDENTIALS_STAGED,
    ROOM_WRITTEN,
    VERIFIED,
    PUBLISHED,
    ACTIVE,
    CLEANED,
    COMPLETED,
    FAILED;

    companion object {
        fun fromStorage(value: String): MutationStage = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown MutationStage: $value")
    }
}

@Entity(
    tableName = "mutation_operations",
    indices = [
        Index(value = ["stage", "updated_at"]),
        Index(value = ["operation_type", "stage"])
    ]
)
data class MutationOperationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "operation_type")
    val operationType: MutationOperationType,
    @ColumnInfo(defaultValue = "'PREPARED'")
    val stage: MutationStage = MutationStage.PREPARED,
    @ColumnInfo(name = "targets_json", defaultValue = "'[]'")
    val targetsJson: String = "[]",
    @ColumnInfo(name = "staged_generation_manifest_json", defaultValue = "'[]'")
    val stagedGenerationManifestJson: String = "[]",
    @ColumnInfo(name = "manifest_version", defaultValue = "1")
    val manifestVersion: Int = 1,
    @ColumnInfo(name = "batch_cursor", defaultValue = "0")
    val batchCursor: Long = 0,
    @ColumnInfo(name = "baseline_revision")
    val baselineRevision: Long,
    @ColumnInfo(name = "error_code", defaultValue = "NULL")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message", defaultValue = "NULL")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "published_at", defaultValue = "NULL")
    val publishedAt: Long? = null,
    @ColumnInfo(name = "completed_at", defaultValue = "NULL")
    val completedAt: Long? = null
)
