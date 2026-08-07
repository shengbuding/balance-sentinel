package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

    @Test
    fun existingLegacyRoomRowsAreExcludedByOperationBaseline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440002"
            db.accountDao().insertCreate(AccountEntity(accountId, 0, "A", ProviderType.DEEPSEEK, activeCredentialGeneration = "g", state = AccountState.VERIFIED, legacyStorageId = "legacy", createdAt = 1, updatedAt = 1))
            db.historyDao().insertBalanceBatch(listOf(BalanceRecordEntity(accountId = accountId, currency = "USD", recordedAt = 1, totalBalance = 99.0, source = BalanceRecordSource.LEGACY_MIGRATION)))
            val source = object : LegacyDataSource {
                override fun read() = LegacyDataSnapshot(records = listOf(RawRecord("legacy", 2, "USD", 1f, 0f, 0f)))
                override fun clear(snapshot: LegacyDataSnapshot) = false
            }
            val result = LegacyDataMigration(db, source).run()
            assertEquals(1, result.records)
        } finally { db.close() }
    }

    @Test
    fun sameMillisecondRecordsRemainDistinctAndBothVerify() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440003"
            db.accountDao().insertCreate(AccountEntity(accountId, 0, "A", ProviderType.DEEPSEEK, activeCredentialGeneration = "g", state = AccountState.VERIFIED, legacyStorageId = "legacy", createdAt = 1, updatedAt = 1))
            val source = object : LegacyDataSource {
                override fun read() = LegacyDataSnapshot(records = listOf(
                    RawRecord("legacy", 3, "USD", 1f, 0f, 0f),
                    RawRecord("legacy", 3, "USD", 2f, 1f, 1f)
                ))
                override fun clear(snapshot: LegacyDataSnapshot) = false
            }
            val result = LegacyDataMigration(db, source).run()
            assertEquals(2, result.records)
            assertEquals(2, db.historyDao().countLegacyRecords())
        } finally { db.close() }
    }

    @Test
    fun legacyMappingManifestResumesAndUpgradesToScopedRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440010"
            insertLegacyAccount(db, accountId)
            val snapshot = LegacyDataSnapshot(
                records = listOf(RawRecord("legacy", 41, "USD", 7f, 2f, 5f))
            )
            val source = retainingSource(snapshot)

            LegacyDataMigration(db, source).run()
            val operation = legacyDataOperation(db)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE balance_records SET migration_operation_id = NULL, migration_source_ordinal = NULL"
            )
            db.openHelper.writableDatabase.execSQL(
                """
                UPDATE mutation_operations
                SET targets_json = ?, manifest_version = 1,
                    stage = 'ROOM_WRITTEN', batch_cursor = 1
                WHERE id = ?
                """.trimIndent(),
                arrayOf("{\"legacy\":\"$accountId\"}", operation.id)
            )

            val resumed = LegacyDataMigration(db, source).run()
            val upgraded = requireNotNull(db.mutationOperationDao().get(operation.id))
            assertEquals(1, resumed.records)
            assertEquals(2, upgraded.manifestVersion)
            assertEquals(
                1,
                queryLong(
                    db,
                    "SELECT COUNT(*) FROM balance_records WHERE migration_operation_id = '${operation.id}'"
                )
            )

            LegacyDataMigration(db, source).run()
            assertEquals(
                1,
                queryLong(
                    db,
                    "SELECT COUNT(*) FROM balance_records WHERE migration_operation_id = '${operation.id}'"
                )
            )
        } finally { db.close() }
    }

    @Test
    fun verifierRejectsTamperedSummaryCloseCountersAndGenerationTime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440011"
            insertLegacyAccount(db, accountId)
            val snapshot = LegacyDataSnapshot(
                summaries = listOf(
                    DailySummary(
                        accountId = "legacy",
                        date = "2026-08-01",
                        currency = "usd",
                        open = 12f,
                        close = 8f,
                        consumed = 6f,
                        toppedUp = 2f,
                        granted = 1f,
                        avgBalance = 9.5f,
                        sampleCount = 4,
                        toppedUpBalanceClose = 20f,
                        grantedBalanceClose = 3f,
                        generatedAt = 1234
                    )
                )
            )
            LegacyDataMigration(db, retainingSource(snapshot)).run()
            val operation = legacyDataOperation(db)
            val manifest = Json.decodeFromString<LegacyMigrationManifest>(operation.targetsJson)
            db.openHelper.writableDatabase.execSQL(
                """
                UPDATE daily_summaries
                SET average_balance = 999, topped_up_balance_close = 998,
                    granted_balance_close = 997, generated_at = 996
                """.trimIndent()
            )

            val failure = runCatching {
                LegacyDataVerifier(db).verify(snapshot, mapOf("legacy" to accountId), operation.id, manifest)
            }.exceptionOrNull()
            assertTrue("all summary fields must be verified", failure != null)
        } finally { db.close() }
    }

    @Test
    fun verifierRejectsTamperedUsagePromptAndCompletionTokens() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440012"
            insertLegacyAccount(db, accountId)
            val snapshot = LegacyDataSnapshot(
                usage = listOf(
                    UsageSnapshot(
                        accountId = "legacy",
                        timestamp = 51,
                        records = listOf(
                            UsageRecord("deepseek-chat", 30, 10, 20),
                            UsageRecord("deepseek-reasoner", 70, 40, 30)
                        )
                    )
                )
            )
            LegacyDataMigration(db, retainingSource(snapshot)).run()
            val operation = legacyDataOperation(db)
            val manifest = Json.decodeFromString<LegacyMigrationManifest>(operation.targetsJson)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE usage_records SET prompt_tokens = prompt_tokens + 1, completion_tokens = completion_tokens - 1"
            )

            val failure = runCatching {
                LegacyDataVerifier(db).verify(snapshot, mapOf("legacy" to accountId), operation.id, manifest)
            }.exceptionOrNull()
            assertTrue("prompt and completion tokens must be verified per record", failure != null)
        } finally { db.close() }
    }

    @Test
    fun verifierRejectsTamperedLogAvailabilityBalancesAndDiagnostics() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440013"
            insertLegacyAccount(db, accountId)
            val snapshot = LegacyDataSnapshot(
                logs = listOf(fullLog(id = 61))
            )
            LegacyDataMigration(db, retainingSource(snapshot)).run()
            val operation = legacyDataOperation(db)
            val manifest = Json.decodeFromString<LegacyMigrationManifest>(operation.targetsJson)
            val insertedId = db.eventLogDao().newest(1).single().id
            db.openHelper.writableDatabase.execSQL(
                """
                UPDATE event_logs
                SET event_type = 'AUTO', is_available = 0,
                    granted_balance_text = 'tampered', topped_up_balance_text = 'tampered',
                    interval_seconds = 999, expected_at = 998,
                    alarm_method = 'tampered', miss_reason = 'tampered'
                WHERE id = $insertedId
                """.trimIndent()
            )

            val failure = runCatching {
                LegacyDataVerifier(db).verify(snapshot, mapOf("legacy" to accountId), operation.id, manifest)
            }.exceptionOrNull()
            assertTrue("all log balance and diagnostic fields must be verified", failure != null)
        } finally { db.close() }
    }

    @Test
    fun existingLogWithSameLegacyIdCannotSatisfyMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            insertLegacyAccount(db, "550e8400-e29b-41d4-a716-446655440014")
            val sourceLog = fullLog(id = 71)
            db.eventLogDao().insertAll(listOf(sourceLog.toEntity()))

            val result = LegacyDataMigration(
                db,
                retainingSource(LegacyDataSnapshot(logs = listOf(sourceLog)))
            ).run()

            assertEquals(1, result.logs)
            assertEquals("the polluted row must be preserved beside the scoped migrated row", 2, db.eventLogDao().countLogs())
        } finally { db.close() }
    }

    @Test
    fun sameMillisecondUsageSnapshotsRemainDistinctAndBothVerify() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            insertLegacyAccount(db, "550e8400-e29b-41d4-a716-446655440015")
            val snapshot = LegacyDataSnapshot(
                usage = listOf(
                    UsageSnapshot("legacy", 81, listOf(UsageRecord("deepseek-chat", 10, 4, 6))),
                    UsageSnapshot("legacy", 81, listOf(UsageRecord("deepseek-reasoner", 20, 8, 12)))
                )
            )

            val result = LegacyDataMigration(db, retainingSource(snapshot)).run()

            assertEquals(2, result.usage)
        } finally { db.close() }
    }

    private suspend fun insertLegacyAccount(db: WalletDatabase, accountId: String) {
        db.accountDao().insertCreate(
            AccountEntity(
                accountId,
                0,
                "A",
                ProviderType.DEEPSEEK,
                activeCredentialGeneration = "g",
                state = AccountState.VERIFIED,
                legacyStorageId = "legacy",
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private fun retainingSource(snapshot: LegacyDataSnapshot) = object : LegacyDataSource {
        override fun read() = snapshot
        override fun clear(snapshot: LegacyDataSnapshot) = false
    }

    private suspend fun legacyDataOperation(db: WalletDatabase) =
        db.mutationOperationDao().listRecoverable().single {
            it.operationType == MutationOperationType.LEGACY_DATA_MIGRATION
        }

    private fun queryLong(db: WalletDatabase, sql: String): Long =
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun fullLog(id: Long) = RefreshLogEntry(
        id = id,
        type = RefreshLogType.SCHEDULE,
        totalBalance = "12.50",
        currency = "USD",
        isAvailable = true,
        grantedBalance = "2.50",
        toppedUpBalance = "10.00",
        timestamp = 91,
        message = "scheduled",
        intervalSeconds = 300,
        expectedTime = 92,
        alarmMethod = "exact",
        missReason = "none"
    )

    private fun RefreshLogEntry.toEntity() = EventLogEntity(
        id = id,
        eventType = EventLogType.valueOf(type.name),
        totalBalanceText = totalBalance,
        currencyText = currency,
        isAvailable = isAvailable,
        grantedBalanceText = grantedBalance,
        toppedUpBalanceText = toppedUpBalance,
        recordedAt = timestamp,
        message = message,
        intervalSeconds = intervalSeconds,
        expectedAt = expectedTime,
        alarmMethod = alarmMethod,
        missReason = missReason
    )
}
