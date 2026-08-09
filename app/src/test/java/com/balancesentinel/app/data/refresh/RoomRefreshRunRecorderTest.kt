package com.balancesentinel.app.data.refresh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultState
import com.balancesentinel.app.data.local.refresh.RefreshRunState
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRefreshRunRecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `begin records running run and one running row per account`() = runTest {
        val account = account("acct-1")
        database.accountDao().insertCreate(accountEntity(account))
        val recorder = RoomRefreshRunRecorder(database)

        val handle = recorder.begin(RefreshTrigger.MANUAL_ALL, listOf(account), 10L, "owner-a")

        assertNotNull(handle.runId)
        assertEquals(RefreshRunState.RUNNING, database.refreshRunDao().getRun(handle.runId)?.state)
        assertEquals(
            RefreshAccountResultState.RUNNING,
            database.refreshRunDao().getAccountResult(handle.runId, account.id)?.state
        )
    }

    @Test
    fun `terminal account result and aggregate are persisted from committed outcomes`() = runTest {
        val account = account("acct-1")
        database.accountDao().insertCreate(accountEntity(account))
        val recorder = RoomRefreshRunRecorder(database)
        val handle = recorder.begin(RefreshTrigger.SERVICE, listOf(account), 10L, "owner-a")

        recorder.recordAccount(
            handle.runId,
            RefreshRequest(account.id, account.revision, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
            AccountRefreshResult.Committed(account.id, balance(account.id)),
        )
        val aggregate = recorder.finish(handle.runId, 20L)

        assertEquals(RefreshBatchState.SUCCEEDED, aggregate.state)
        assertEquals(RefreshRunState.SUCCEEDED, database.refreshRunDao().getRun(handle.runId)?.state)
        assertEquals(
            RefreshAccountResultState.SUCCEEDED,
            database.refreshRunDao().getAccountResult(handle.runId, account.id)?.state
        )
    }

    @Test
    fun `startup recovery interrupts runs without an active owner`() = runTest {
        val account = account("acct-1")
        database.accountDao().insertCreate(accountEntity(account))
        val recorder = RoomRefreshRunRecorder(database)
        val handle = recorder.begin(RefreshTrigger.SERVICE, listOf(account), 10L, "dead-owner")

        recorder.recover("active-owner", 50L)

        assertEquals(RefreshRunState.INTERRUPTED, database.refreshRunDao().getRun(handle.runId)?.state)
        assertEquals(
            RefreshAccountResultState.INTERRUPTED,
            database.refreshRunDao().getAccountResult(handle.runId, account.id)?.state
        )
    }

    private fun account(id: String) = AccountInfo(
        id = id,
        label = id,
        apiKey = "api-key-$id",
        providerType = ProviderType.DEEPSEEK,
        revision = 0
    )

    private fun accountEntity(account: AccountInfo) = AccountEntity(
        id = account.id,
        displayOrder = 0,
        label = account.label,
        providerType = account.providerType,
        activeCredentialGeneration = "generation",
        revision = account.revision,
        state = AccountState.VERIFIED,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun balance(accountId: String) = UnifiedBalance(
        provider = ProviderType.DEEPSEEK,
        accountId = accountId,
        isAvailable = true,
        balances = listOf(BalanceEntry("USD", 1.0))
    )
}
