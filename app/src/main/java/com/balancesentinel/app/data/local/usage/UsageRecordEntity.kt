package com.balancesentinel.app.data.local.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "usage_records",
    primaryKeys = ["snapshot_id", "record_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = UsageSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["snapshot_id", "model_name"])]
)
data class UsageRecordEntity(
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    @ColumnInfo(name = "record_ordinal")
    val recordOrdinal: Int,
    @ColumnInfo(name = "model_name")
    val modelName: String,
    @ColumnInfo(name = "total_tokens", defaultValue = "0")
    val totalTokens: Long = 0,
    @ColumnInfo(name = "prompt_tokens", defaultValue = "0")
    val promptTokens: Long = 0,
    @ColumnInfo(name = "completion_tokens", defaultValue = "0")
    val completionTokens: Long = 0
)
