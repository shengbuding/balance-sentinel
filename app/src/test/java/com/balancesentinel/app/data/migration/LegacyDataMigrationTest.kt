package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyDataMigrationTest {
    @Test
    fun emptySourceIsAStableNoOp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            var cleared = false
            val result = LegacyDataMigration(db, object : LegacyDataSource {
                override fun read() = LegacyDataSnapshot()
                override fun clear(): Boolean { cleared = true; return true }
            }).run()
            assertEquals(0, result.records)
            assertTrue(!cleared)
        } finally { db.close() }
    }

    @Test
    fun batchSizeAndStagesAreDurable() {
        assertEquals(500, LegacyDataMigration.BATCH_SIZE)
        assertTrue(LegacyAccountMigrationStage.entries.contains(LegacyAccountMigrationStage.ACTIVE))
        assertTrue(LegacyAccountMigrationStage.entries.contains(LegacyAccountMigrationStage.CLEANED))
    }
}
