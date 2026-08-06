package com.balancesentinel.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.model.AccountInfo
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomAccountUiRepositoryTest {
    @Test
    fun `verified Room metadata with missing credential payload is corrupt`() = withRepository(
        CredentialReadResult.Missing
    ) { repository, _ ->
        assertTrue(repository.terminalState() is AccountLoadState.Corrupt)
    }

    @Test
    fun `credential corruption remains the typed repository error`() {
        val corruption = DataCorruptionException("encrypted payload damaged")
        withRepository(CredentialReadResult.Corrupt(corruption)) { repository, _ ->
            val state = repository.terminalState()
            assertTrue(state is AccountLoadState.Corrupt)
            assertSame(corruption, (state as AccountLoadState.Corrupt).error)
        }
    }

    @Test
    fun `credential payload that does not match verified Room metadata is corrupt`() = withRepository(
        validResult(payloadAccount().copy(id = "different-legacy-id"))
    ) { repository, _ ->
        assertTrue(repository.terminalState() is AccountLoadState.Corrupt)
    }

    @Test
    fun `valid payload reconciles on the injected background dispatcher`() = withRepository(
        validResult(payloadAccount())
    ) { repository, store ->
        val state = repository.terminalState()

        assertTrue(state is AccountLoadState.Ready)
        val account = (state as AccountLoadState.Ready).accounts.single()
        assertEquals(ROOM_ID, account.id)
        assertEquals("Room label", account.label)
        assertEquals("sk-room-ui-secret", account.apiKey)
        assertTrue(store.readThreadName?.startsWith("room-account-ui-test-io") == true)
        assertFalse("Credential reconciliation must not execute on Android main", store.readOnMainLooper)
    }

    @Test
    fun `fresh subscription recovers after credential payload becomes valid`() = withRepository(
        CredentialReadResult.Corrupt(DataCorruptionException("temporarily unreadable"))
    ) { repository, store ->
        assertTrue(repository.terminalState() is AccountLoadState.Corrupt)

        store.result = validResult(payloadAccount())

        val recovered = repository.terminalState()
        assertTrue(recovered is AccountLoadState.Ready)
        assertEquals(ROOM_ID, (recovered as AccountLoadState.Ready).accounts.single().id)
        assertEquals(2, store.readCount)
    }

    private fun withRepository(
        initialResult: CredentialReadResult,
        block: suspend (RoomAccountUiRepository, MutableCredentialStore) -> Unit
    ) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "room-account-ui-test-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val store = MutableCredentialStore(initialResult)
        try {
            database.accountDao().insertCreate(roomAccount())
            val repository = RoomAccountUiRepository(
                RoomAccountRepository(database),
                store,
                dispatcher
            )
            block(repository, store)
        } finally {
            dispatcher.close()
            database.close()
        }
    }

    private suspend fun RoomAccountUiRepository.terminalState(): AccountLoadState =
        observe().first { it !is AccountLoadState.Loading }

    private fun roomAccount() = AccountEntity(
        id = ROOM_ID,
        displayOrder = 0,
        label = "Room label",
        providerType = ProviderType.DEEPSEEK,
        providerConfigJson = "{}",
        activeCredentialGeneration = "encrypted:test",
        revision = 0,
        state = AccountState.VERIFIED,
        legacyStorageId = LEGACY_ID,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun payloadAccount() = AccountInfo(
        id = LEGACY_ID,
        label = "Credential label",
        apiKey = "sk-room-ui-secret",
        providerType = ProviderType.DEEPSEEK,
        revision = 0
    )

    private fun validResult(account: AccountInfo) = CredentialReadResult.Valid(
        CredentialPayload(listOf(account)),
        CredentialGeneration.ENCRYPTED_PREFERENCES
    )

    private class MutableCredentialStore(
        var result: CredentialReadResult
    ) : CredentialStore {
        var readCount: Int = 0
            private set
        var readThreadName: String? = null
            private set
        var readOnMainLooper: Boolean = false
            private set

        override fun read(): CredentialReadResult {
            readCount++
            readThreadName = Thread.currentThread().name
            readOnMainLooper = Looper.myLooper() == Looper.getMainLooper()
            return result
        }

        override suspend fun write(payload: CredentialPayload) {
            result = CredentialReadResult.Valid(payload, CredentialGeneration.ENCRYPTED_PREFERENCES)
        }

        override suspend fun clear() {
            result = CredentialReadResult.Missing
        }
    }

    private companion object {
        const val ROOM_ID = "7ad55a79-302a-4c3e-b26f-a41fe74e32cf"
        const val LEGACY_ID = "legacy-room-ui-id"
    }
}
