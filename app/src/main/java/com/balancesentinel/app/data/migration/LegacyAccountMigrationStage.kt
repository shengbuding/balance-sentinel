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
    FAILED
}
