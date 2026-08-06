package com.balancesentinel.app.data.migration

/** Durable stages used by the legacy account migration. */
enum class LegacyAccountMigrationStage {
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
        fun fromStorage(value: String): LegacyAccountMigrationStage =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Unknown legacy migration stage: $value")
    }
}
