package com.balancesentinel.app.data.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyDataMigrationLargeDatasetTest {
    @Test
    fun ninetyThousandRecordsUseFiveHundredRecordBatches() {
        assertEquals(180, 90_000 / LegacyDataMigration.BATCH_SIZE)
    }
}
