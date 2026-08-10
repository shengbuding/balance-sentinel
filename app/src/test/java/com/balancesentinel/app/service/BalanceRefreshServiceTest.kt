package com.balancesentinel.app.service

import android.content.Context
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.AccountStoreRead
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.testing.MutableSettingsRepository
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceRefreshServiceTest {
    @Before
    fun setUp() {
        val settingsRepository = MutableSettingsRepository()
        SettingsRepositoryProvider.factory = { settingsRepository }
    }

    @After
    fun tearDown() {
        SettingsRepositoryProvider.resetForTests()
    }

    @Test
    fun `task removal does not restart bounded monitoring session`() {
        val service = Robolectric.buildService(BalanceRefreshService::class.java).get()
        val starter = RecordingStarter()
        service.serviceStarter = starter

        service.onTaskRemoved(null)

        assertEquals(0, starter.calls)
    }

    @Test
    fun `service batch reports repository account count for deadline sizing`() = runTest {
        val runner = BalanceRefreshRunner(
            gateway = EmptyGateway(),
            committedBalanceReader = { emptyList() },
            accountSnapshotReader = ServiceAccountSnapshotReader {
                AccountStoreRead.Ready(listOf(account("one"), account("two")))
            }
        )

        assertEquals(2, runner.refreshBatch().accountCount)
    }

    @Test
    fun `empty repository batch does not reuse old committed balances`() = runTest {
        val stale = AccountBalance("deleted", "Deleted", "99", "CNY", true, "", "", 1L)
        val runner = BalanceRefreshRunner(
            gateway = EmptyGateway(),
            committedBalanceReader = { listOf(stale) },
            accountSnapshotReader = ServiceAccountSnapshotReader {
                AccountStoreRead.Ready(emptyList())
            }
        )

        assertTrue(runner.refreshBatch().committedBalances.isEmpty())
    }

    @Test
    fun `service runner reads live account snapshot after refresh`() = runTest {
        var current = AccountStoreRead.Ready(listOf(account("deleted")))
        val gateway = object : RefreshGateway {
            override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
                AccountRefreshResult.Skipped(accountId, "unused")

            override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
                current = AccountStoreRead.Ready(emptyList())
                return RefreshBatchResult("test-run", emptyList(), deriveRefreshBatchAggregate(emptyList()))
            }

            override fun invalidate(accountId: String) = Unit

            override suspend fun readAccountSnapshot() = current
        }
        val stale = AccountBalance("deleted", "Deleted", "99", "CNY", true, "", "", 1L)
        val runner = buildServiceRefreshRunner(
            gateway = gateway,
            accountReader = ServiceAccountSnapshotReader { gateway.readAccountSnapshot() },
            committedBalanceReader = { listOf(stale) }
        )

        assertTrue(runner.refreshBatch().committedBalances.isEmpty())
    }

    private fun account(id: String) = AccountInfo(id, id, "key-$id", ProviderType.DEEPSEEK, revision = 1)

    private class EmptyGateway : RefreshGateway {
        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult =
            AccountRefreshResult.Skipped(accountId, "unused")
        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult =
            RefreshBatchResult("test-run", emptyList(), deriveRefreshBatchAggregate(emptyList()))
        override fun invalidate(accountId: String) = Unit
    }

    private class RecordingStarter : ServiceStarter {
        var calls = 0

        override fun start(context: Context): ServiceStartResult {
            calls += 1
            return ServiceStartResult.Started
        }
    }
}
