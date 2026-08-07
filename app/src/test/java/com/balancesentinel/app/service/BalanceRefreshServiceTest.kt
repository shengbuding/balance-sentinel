package com.balancesentinel.app.service

import android.content.Context
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.AccountStoreRead
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceRefreshServiceTest {

    @Test
    fun `task removal restart delegates to foreground service starter`() {
        val service = Robolectric.buildService(BalanceRefreshService::class.java).get()
        val starter = RecordingStarter()
        service.serviceStarter = starter

        service.onTaskRemoved(null)

        assertEquals(1, starter.calls)
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

    private fun account(id: String) = AccountInfo(id, id, "key-$id", ProviderType.DEEPSEEK, revision = 1)

    private class EmptyGateway : RefreshGateway {
        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult =
            AccountRefreshResult.Skipped(accountId, "unused")
        override suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult> = emptyList()
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
