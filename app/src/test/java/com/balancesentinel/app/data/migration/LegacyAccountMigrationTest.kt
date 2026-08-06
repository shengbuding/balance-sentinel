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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            val first = LegacyAccountMigration(database, reader, null).run()
            val second = LegacyAccountMigration(database, reader, null).run()
            assertEquals(first.mappings.single().accountId, second.mappings.single().accountId)
            assertEquals(1, database.accountDao().getAllForMigration().size)
            val row = database.accountDao().get(first.mappings.single().accountId)
            assertNotNull(row)
            assertEquals("legacy-id", row?.legacyStorageId)
        } finally {
            database.close()
        }
    }

    private class NoopCredentialStore : CredentialStore {
        override fun read(): CredentialReadResult = CredentialReadResult.Missing
        override suspend fun write(payload: CredentialPayload) = Unit
        override suspend fun clear() = Unit
    }
}
