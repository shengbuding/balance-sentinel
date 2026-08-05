package com.balancesentinel.app.data.local.metadata

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LegacyMigrationStage {
    NONE,
    DISCOVERED,
    VALIDATED,
    CREDENTIALS_STAGED,
    ROOM_WRITTEN,
    VERIFIED,
    ACTIVE,
    CLEANED,
    FAILED;

    companion object {
        fun fromStorage(value: String): LegacyMigrationStage = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown LegacyMigrationStage: $value")
    }
}

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "0")
    val id: Int = 0,
    @ColumnInfo(name = "local_revision", defaultValue = "0")
    val localRevision: Long = 0,
    @ColumnInfo(name = "active_data_generation", defaultValue = "'LEGACY'")
    val activeDataGeneration: String = "LEGACY",
    @ColumnInfo(name = "legacy_migration_stage", defaultValue = "'NONE'")
    val legacyMigrationStage: LegacyMigrationStage = LegacyMigrationStage.NONE,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
