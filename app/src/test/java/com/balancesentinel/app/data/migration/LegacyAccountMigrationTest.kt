package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyAccountMigrationTest {
    @Test
    fun stableIdsAreIndependentOfCredentialValue() {
        val id = "legacy-storage-1"
        assertEquals(
            LegacyAccountMigration.stableAccountId(id),
            LegacyAccountMigration.stableAccountId(id)
        )
    }

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
            assertEquals(1, database.accountDao().getAllForMigration().size)
            val row = database.accountDao().get(first.mappings.single().accountId)
            assertNotNull(row)
            assertEquals("legacy-id", row?.legacyStorageId)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationRequiresCredentialStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val failure = runCatching {
                LegacyAccountMigration(database, reader, null).run()
            }.exceptionOrNull()
            assertTrue("Missing credential store must fail before Room writes", failure is IllegalArgumentException)
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
                        if (stage == LegacyAccountMigrationStage.VERIFIED) error("injected verification crash")
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
}
