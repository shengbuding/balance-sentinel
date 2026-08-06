package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.migration.LegacyAccountMigration
import com.balancesentinel.app.data.model.AccountDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {
    @Test
    fun apiKeyChangeKeepsStableAccountUuidThroughExistingManagerEntryPoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("task3-red", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val manager = ApiKeyManager(context, prefs)
        val created = manager.addAccount("Legacy", "key-before")

        manager.saveAccount(
            created.id,
            AccountDraft(
                label = "Legacy",
                apiKey = "key-after",
                providerType = ProviderType.DEEPSEEK
            )
        )

        assertEquals("Account identity must not be derived from API key", created.id, manager.getAccounts().single().id)
    }

    @Test
    fun apiKeyRotationKeepsIndependentCanonicalRoomUuidThroughManagerSeam() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("task3-room-rotation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val manager = ApiKeyManager(context, prefs)
        val legacy = manager.addAccount("Legacy", "key-before")
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val store = InMemoryCredentialStore()
        try {
            val first = LegacyAccountMigration(database, manager.legacyAccountReader(), store).run()
            manager.saveAccount(
                legacy.id,
                AccountDraft("Legacy", "key-after", ProviderType.DEEPSEEK)
            )
            val second = LegacyAccountMigration(database, manager.legacyAccountReader(), store).run()

            val roomId = first.mappings.single().accountId
            assertEquals(roomId, UUID.fromString(roomId).toString())
            assertEquals(roomId, second.mappings.single().accountId)
            assertTrue(roomId != manager.computeId("key-before"))
            assertTrue(roomId != manager.computeId("key-after"))
            assertEquals(1, database.accountDao().getAllForMigration().size)
        } finally {
            database.close()
            prefs.edit().clear().commit()
        }
    }

    private class InMemoryCredentialStore : CredentialStore {
        private var payload: CredentialPayload? = null
        override fun read(): CredentialReadResult = payload?.let {
            CredentialReadResult.Valid(it, CredentialGeneration.ENCRYPTED_PREFERENCES)
        } ?: CredentialReadResult.Missing
        override suspend fun write(payload: CredentialPayload) {
            this.payload = payload
        }
        override suspend fun clear() {
            payload = null
        }
    }
}
