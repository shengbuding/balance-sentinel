package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LegacyAccountMigrationTest {
    @Test
    fun rerunReusesStableUuidAndDoesNotDuplicateRoomRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val account = AccountInfo("legacy-id", "Legacy", "key-before", ProviderType.DEEPSEEK)
            val reader = object : LegacyAccountReader {
                override fun read(): CredentialReadResult = CredentialReadResult.Valid(
                    CredentialPayload(listOf(account)),
                    com.balancesentinel.app.data.credentials.CredentialGeneration.LEGACY
                )
            }
            val first = LegacyAccountMigration(database, reader, RecordingCredentialStore()).run()
            val second = LegacyAccountMigration(database, reader, RecordingCredentialStore()).run()
            assertEquals(first.mappings.single().accountId, second.mappings.single().accountId)
            assertEquals(first.mappings.single().accountId, UUID.fromString(first.mappings.single().accountId).toString())
            assertTrue(first.mappings.single().accountId != account.id)
            assertEquals(1, database.accountDao().getAllForMigration().size)
            val row = database.accountDao().get(first.mappings.single().accountId)
            assertNotNull(row)
            assertEquals("legacy-id", row?.legacyStorageId)
            val operation = database.mutationOperationDao().listRecoverable().single()
            assertEquals(MutationStage.VERIFIED, operation.stage)
            assertTrue(operation.stagedGenerationManifestJson.contains(first.mappings.single().credentialGeneration))
        } finally {
            database.close()
        }
    }

    @Test
    fun missingCredentialReadbackFailsBeforeRoomWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val failure = runCatching {
                LegacyAccountMigration(database, reader, MissingReadbackCredentialStore).run()
            }.exceptionOrNull()
            assertTrue("Missing credential readback must fail before Room writes", failure is IllegalArgumentException)
            assertTrue(database.accountDao().getAllForMigration().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun operationManifestExistsBeforeCredentialStaging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val store = RecordingCredentialStore(database)
            LegacyAccountMigration(database, reader, store).run()
            assertTrue(store.operationSeenBeforeWrite)
            assertTrue(store.manifestSeenBeforeWrite)
        } finally {
            database.close()
        }
    }

    @Test
    fun verificationCrashLeavesAccountHiddenAndOperationWrittenStage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val failure = runCatching {
                LegacyAccountMigration(
                    database,
                    reader,
                    RecordingCredentialStore(),
                    onStage = { stage ->
                        if (stage == LegacyAccountMigrationStage.ROOM_WRITTEN) error("injected Room-written crash")
                    }
                ).run()
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(database.accountDao().observeVerified().first().isEmpty())
            assertTrue(database.mutationOperationDao().listRecoverable().isNotEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun everyDurableStageRecoversWithoutDuplicatesAndRetainsLegacyJson() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = Json { encodeDefaults = true }
        val raw = json.encodeToString(listOf(AccountInfo("legacy-id", "Legacy", "key")))
        val stages = listOf(
            LegacyAccountMigrationStage.DISCOVERED,
            LegacyAccountMigrationStage.VALIDATED,
            LegacyAccountMigrationStage.CREDENTIALS_STAGED,
            LegacyAccountMigrationStage.ROOM_WRITTEN,
            LegacyAccountMigrationStage.VERIFIED
        )

        stages.forEach { crashStage ->
            val prefs = context.getSharedPreferences("task3-stage-${crashStage.name}", Context.MODE_PRIVATE)
            prefs.edit().clear().putString(LegacyAccountSource.KEY_ACCOUNTS, raw).commit()
            val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
            val store = RecordingCredentialStore()
            try {
                val failure = runCatching {
                    LegacyAccountMigration(
                        database,
                        LegacyAccountSource(prefs),
                        store,
                        onStage = { stage -> if (stage == crashStage) error("crash at $stage") }
                    ).run()
                }.exceptionOrNull()
                assertNotNull("Expected injected crash at $crashStage", failure)
                assertEquals(raw, prefs.getString(LegacyAccountSource.KEY_ACCOUNTS, null))
                if (crashStage == LegacyAccountMigrationStage.ROOM_WRITTEN) {
                    assertTrue(database.accountDao().observeVerified().first().isEmpty())
                    assertTrue(database.accountDao().getAllForMigration().all { it.state == AccountState.PENDING })
                }

                val recovered = LegacyAccountMigration(
                    database,
                    LegacyAccountSource(prefs),
                    store
                ).run()
                assertEquals(LegacyAccountMigrationStage.VERIFIED, recovered.stage)
                assertEquals(1, database.accountDao().getAllForMigration().size)
                assertEquals(1, database.accountDao().observeVerified().first().size)
                assertTrue(store.read() is CredentialReadResult.Valid)
                assertEquals(raw, prefs.getString(LegacyAccountSource.KEY_ACCOUNTS, null))
            } finally {
                database.close()
                prefs.edit().clear().commit()
            }
        }
    }

    @Test
    fun corruptLegacyJsonDoesNotCreateRoomAccountsOrOverwriteSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("task3-corrupt", Context.MODE_PRIVATE)
        val raw = "{ definitely not valid account JSON"
        prefs.edit().clear().putString(LegacyAccountSource.KEY_ACCOUNTS, raw).commit()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val failure = runCatching {
                LegacyAccountMigration(database, LegacyAccountSource(prefs), RecordingCredentialStore()).run()
            }.exceptionOrNull()
            assertTrue(failure is DataCorruptionException)
            assertTrue(database.accountDao().getAllForMigration().isEmpty())
            assertEquals(raw, prefs.getString(LegacyAccountSource.KEY_ACCOUNTS, null))
        } finally {
            database.close()
            prefs.edit().clear().commit()
        }
    }

    private fun validReader(account: AccountInfo): LegacyAccountReader =
        LegacyAccountReader {
            CredentialReadResult.Valid(
                CredentialPayload(listOf(account)),
                com.balancesentinel.app.data.credentials.CredentialGeneration.LEGACY
            )
        }

    private class RecordingCredentialStore(
        private val database: WalletDatabase? = null
    ) : CredentialStore {
        var operationSeenBeforeWrite = false
        var manifestSeenBeforeWrite = false
        private var payload: CredentialPayload? = null

        override fun read(): CredentialReadResult = payload?.let {
            CredentialReadResult.Valid(it, com.balancesentinel.app.data.credentials.CredentialGeneration.ENCRYPTED_PREFERENCES)
        } ?: CredentialReadResult.Missing

        override suspend fun write(payload: CredentialPayload) {
            this.payload = payload
            database?.let {
                val operation = it.mutationOperationDao().listRecoverable().firstOrNull()
                operationSeenBeforeWrite = operation != null
                manifestSeenBeforeWrite = operation?.stagedGenerationManifestJson?.contains("legacy:") == true
            }
        }

        override suspend fun clear() = Unit
    }

    private object MissingReadbackCredentialStore : CredentialStore {
        override fun read(): CredentialReadResult = CredentialReadResult.Missing
        override suspend fun write(payload: CredentialPayload) = Unit
        override suspend fun clear() = Unit
    }
}
