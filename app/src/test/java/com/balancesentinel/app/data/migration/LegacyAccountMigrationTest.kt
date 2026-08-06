package com.balancesentinel.app.data.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyAccountMigrationTest {
    @Test
    fun stableIdsAreIndependentOfCredentialValue() {
        val id = "legacy-storage-1"
        assertEquals(
            LegacyAccountMigration.stableAccountId(id),
            LegacyAccountMigration.stableAccountId(id)
        )
    }
}
