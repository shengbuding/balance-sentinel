package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyDataMigrationLargeDatasetTest {
    @Test
    fun ninetyThousandRoomRowsCommitInFiveHundredRecordTransactions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440090"
            db.accountDao().insertCreate(
                AccountEntity(
                    accountId,
                    0,
                    "Large",
                    ProviderType.DEEPSEEK,
                    activeCredentialGeneration = "g",
                    state = AccountState.VERIFIED,
                    legacyStorageId = "legacy",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
            db.openHelper.writableDatabase.execSQL(
                "CREATE TABLE record_batch_audit(batch_cursor INTEGER NOT NULL, record_count INTEGER NOT NULL)"
            )
            db.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER audit_legacy_record_batch
                AFTER UPDATE OF batch_cursor ON mutation_operations
                WHEN NEW.operation_type = 'LEGACY_DATA_MIGRATION'
                    AND NEW.batch_cursor > OLD.batch_cursor
                BEGIN
                    INSERT INTO record_batch_audit(batch_cursor, record_count)
                    SELECT NEW.batch_cursor, COUNT(*)
                    FROM balance_records
                    WHERE source = 'LEGACY_MIGRATION';
                END
                """.trimIndent()
            )
            val snapshot = LegacyDataSnapshot(
                records = List(90_000) { index ->
                    RawRecord(
                        accountId = "legacy",
                        timestamp = index.toLong(),
                        currency = "USD",
                        totalBalance = index.toFloat(),
                        grantedBalance = (index % 13).toFloat(),
                        toppedUpBalance = (index % 17).toFloat()
                    )
                }
            )
            val source = object : LegacyDataSource {
                override fun read() = snapshot
                override fun clear(snapshot: LegacyDataSnapshot) = false
            }

            val result = LegacyDataMigration(db, source).run()

            assertEquals(90_000, result.records)
            assertEquals(90_000, db.historyDao().countLegacyRecords())
            val operation = db.mutationOperationDao().listRecoverable().single {
                it.operationType == MutationOperationType.LEGACY_DATA_MIGRATION
            }
            assertEquals(90_000, operation.batchCursor)
            db.openHelper.readableDatabase.query(
                "SELECT batch_cursor, record_count FROM record_batch_audit ORDER BY batch_cursor"
            ).use { cursor ->
                assertEquals(180, cursor.count)
                var batch = 1
                while (cursor.moveToNext()) {
                    val expectedCursor = batch * LegacyDataMigration.BATCH_SIZE
                    assertEquals(expectedCursor.toLong(), cursor.getLong(0))
                    assertEquals(expectedCursor.toLong(), cursor.getLong(1))
                    batch += 1
                }
            }
        } finally { db.close() }
    }
}
