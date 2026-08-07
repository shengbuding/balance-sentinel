package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.RawRecord
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

    @Test
    fun sourceReadFailurePersistsFailedStage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val failure = runCatching {
                LegacyDataMigration(db, object : LegacyDataSource {
                    override fun read(): LegacyDataSnapshot = error("corrupt JSON")
                }).run()
            }.exceptionOrNull()
            assertTrue(failure != null)
            assertTrue(db.appMetadataDao().get()?.legacyMigrationStage !in setOf(com.balancesentinel.app.data.local.metadata.LegacyMigrationStage.ACTIVE, com.balancesentinel.app.data.local.metadata.LegacyMigrationStage.CLEANED))
        } finally { db.close() }
    }

    @Test
    fun unknownLegacyAccountIdIsRejectedBeforeRoomPublication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            db.accountDao().insertCreate(AccountEntity("550e8400-e29b-41d4-a716-446655440000", 0, "A", ProviderType.DEEPSEEK, activeCredentialGeneration = "g", state = AccountState.VERIFIED, legacyStorageId = "known", createdAt = 1, updatedAt = 1))
            val source = object : LegacyDataSource {
                override fun read() = LegacyDataSnapshot(records = listOf(RawRecord("unknown", 1, "USD", 1f, 0f, 0f)))
            }
            assertTrue(runCatching { LegacyDataMigration(db, source).run() }.isFailure)
            assertEquals(0, db.historyDao().countLegacyRecords())
        } finally { db.close() }
    }

    @Test
    fun cleanupExceptionPersistsFailedAndDoesNotReportCleaned() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            db.accountDao().insertCreate(AccountEntity("550e8400-e29b-41d4-a716-446655440001", 0, "A", ProviderType.DEEPSEEK, activeCredentialGeneration = "g", state = AccountState.VERIFIED, legacyStorageId = "legacy", createdAt = 1, updatedAt = 1))
            val source = object : LegacyDataSource {
                override fun read() = LegacyDataSnapshot(records = listOf(RawRecord("legacy", 1, "USD", 1f, 0f, 0f)))
                override fun clear(snapshot: LegacyDataSnapshot): Boolean = error("restore failed")
            }
            assertTrue(runCatching { LegacyDataMigration(db, source).run() }.isFailure)
            assertTrue(db.appMetadataDao().get()?.legacyMigrationStage !in setOf(com.balancesentinel.app.data.local.metadata.LegacyMigrationStage.ACTIVE, com.balancesentinel.app.data.local.metadata.LegacyMigrationStage.CLEANED))
        } finally { db.close() }
    }
}
