package com.balancesentinel.app.service

import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.AccountStoreRead
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that [BalanceRefreshRunner] routes through the shared [RefreshGateway]
 * with [RefreshTrigger.SERVICE] and reads committed Widget storage via
 * an injected reader — not by calling the fake gateway or store directly.
 *
 * These tests instantiate the real production [BalanceRefreshRunner],
 * not a fake. A test that calls the gateway/store directly is forbidden.
 */
class BalanceRefreshRunnerTest {

    // Service runner must call refreshAll with SERVICE trigger.
    @Test
    fun `service runner routes through gateway with SERVICE trigger`() = runTest {
        val gateway = DistinguishingRefreshGateway()

        val runner = BalanceRefreshRunner(gateway) { emptyList() }
        runner.refreshAndReadCommitted()

        assertEquals("refreshAll must be called exactly once", 1, gateway.refreshAllCalls.size)
        assertEquals(RefreshTrigger.SERVICE, gateway.refreshAllCalls[0])
        assertTrue("refreshAccount must not be called directly", gateway.refreshAccountCalls.isEmpty())
    }

    // Service runner reads committed storage AFTER gateway completion.
    @Test
    fun `service runner reads committed balances after gateway completion`() = runTest {
        val expectedBalances = listOf(
            AccountBalance("acc-1", "Test", "150.00", "CNY", true, "120.00", "30.00", 0L)
        )
        val gateway = DistinguishingRefreshGateway()

        val runner = BalanceRefreshRunner(gateway) { expectedBalances }
        val result = runner.refreshAndReadCommitted()

        assertEquals("Must return committed balances from reader", expectedBalances, result)
    }

    // Service runner handles empty committed storage gracefully.
    @Test
    fun `service runner returns empty list when no committed balances exist`() = runTest {
        val gateway = DistinguishingRefreshGateway()

        val runner = BalanceRefreshRunner(gateway) { emptyList() }
        val result = runner.refreshAndReadCommitted()

        assertTrue("Must return empty list when no committed balances", result.isEmpty())
    }

    // Service runner returns committed data even when gateway returns failures
    // (failures don't clear committed storage — stale data remains).
    @Test
    fun `service runner returns committed data even when gateway returns failures`() = runTest {
        val staleBalances = listOf(
            AccountBalance("acc-1", "Stale", "100.00", "USD", true, "80.00", "20.00", 0L)
        )
        val gateway = DistinguishingRefreshGateway(
            AccountRefreshResult.Failed("acc-1", RefreshFailure.NetworkFailure("timeout"))
        )

        val runner = BalanceRefreshRunner(gateway) { staleBalances }
        val result = runner.refreshAndReadCommitted()

        assertEquals("Must return committed data even after failure", staleBalances, result)
    }

    @Test
    fun `service runner does not read stale cache after an empty account snapshot`() = runTest {
        val staleBalances = listOf(
            AccountBalance("deleted", "Deleted", "100.00", "USD", true, "", "", 1L)
        )
        val runner = BalanceRefreshRunner(
            gateway = DistinguishingRefreshGateway(),
            accountSnapshotReader = ServiceAccountSnapshotReader {
                AccountStoreRead.Ready(emptyList())
            },
            committedBalanceReader = { staleBalances }
        )

        assertTrue(runner.refreshAndReadCommitted().isEmpty())
    }

    @Test
    fun `service batch filters deleted accounts from the post-refresh snapshot`() = runTest {
        val staleBalances = listOf(
            AccountBalance("active", "Active", "100.00", "USD", true, "", "", 1L),
            AccountBalance("deleted", "Deleted", "99.00", "USD", true, "", "", 1L)
        )
        var reads = 0
        val runner = BalanceRefreshRunner(
            gateway = DistinguishingRefreshGateway(),
            accountSnapshotReader = ServiceAccountSnapshotReader {
                reads += 1
                if (reads == 1) {
                    AccountStoreRead.Ready(listOf(
                        AccountInfo("active", "Active", "key", ProviderType.DEEPSEEK, revision = 1),
                        AccountInfo("deleted", "Deleted", "key", ProviderType.DEEPSEEK, revision = 1)
                    ))
                } else {
                    AccountStoreRead.Ready(listOf(
                        AccountInfo("active", "Active", "key", ProviderType.DEEPSEEK, revision = 1)
                    ))
                }
            },
            committedBalanceReader = { staleBalances }
        )

        assertEquals(listOf("active"), runner.refreshBatch().committedBalances.map { it.accountId })
    }

    @Test
    fun `service runner preserves the real gateway batch aggregate`() = runTest {
        val gateway = DistinguishingRefreshGateway(
            AccountRefreshResult.Failed("acc-1", RefreshFailure.AuthenticationFailure("bad key"))
        )
        val batch = BalanceRefreshRunner(gateway) { emptyList() }.refreshBatch()

        assertTrue("runner must expose the durable batch result", batch.batch != null)
        assertEquals(1, batch.batch?.results?.size)
        assertEquals("acc-1", batch.batch?.results?.single()?.accountId)
    }

    @Test
    fun `service runner marks deadline immediately before refresh and clears after committed read`() = runTest {
        val events = mutableListOf<String>()
        val gateway = EventGateway(events)
        val lifecycle = EventDeadlineLifecycle(events)
        val runner = BalanceRefreshRunner(gateway, lifecycle) {
            events += "read"
            emptyList()
        }

        runner.refreshAndReadCommitted()

        assertEquals(listOf("deadline", "refresh", "read", "clear"), events)
    }

    @Test
    fun `service runner clears deadline when refresh fails`() = runTest {
        val events = mutableListOf<String>()
        val lifecycle = EventDeadlineLifecycle(events)
        val gateway = object : RefreshGateway {
            override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
                events += "refresh"
                throw IllegalStateException("failed")
            }

            override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
                error("not used")

            override fun invalidate(accountId: String) = Unit
        }

        try {
            BalanceRefreshRunner(gateway, lifecycle) { emptyList() }.refreshAndReadCommitted()
        } catch (_: IllegalStateException) {
            // The gateway failure is expected; lifecycle cleanup is the behavior under test.
        }

        assertEquals(listOf("deadline", "refresh", "clear"), events)
    }

    @Test
    fun `service runner clears deadline when refresh is cancelled`() = runTest {
        val events = mutableListOf<String>()
        val lifecycle = EventDeadlineLifecycle(events)
        val gateway = object : RefreshGateway {
            override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
                events += "refresh"
                awaitCancellation()
            }

            override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
                error("not used")

            override fun invalidate(accountId: String) = Unit
        }
        val job = launch {
            BalanceRefreshRunner(gateway, lifecycle) { emptyList() }.refreshAndReadCommitted()
        }

        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(listOf("deadline", "refresh", "clear"), events)
    }

    private class EventDeadlineLifecycle(
        private val events: MutableList<String>
    ) : RefreshDeadlineLifecycle {
        override fun markStarted() {
            events += "deadline"
        }

        override fun clear() {
            events += "clear"
        }
    }

    private class EventGateway(
        private val events: MutableList<String>
    ) : RefreshGateway {
        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
            events += "refresh"
            return batch(emptyList())
        }

        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
            error("not used")

        override fun invalidate(accountId: String) = Unit
    }

    private class DistinguishingRefreshGateway(
        vararg private val results: AccountRefreshResult
    ) : RefreshGateway {
        val refreshAllCalls = mutableListOf<RefreshTrigger>()
        val refreshAccountCalls = mutableListOf<Pair<String, RefreshTrigger>>()
        private val resultsList = results.toMutableList()

        override suspend fun refreshAccount(
            accountId: String,
            trigger: RefreshTrigger
        ): AccountRefreshResult {
            refreshAccountCalls += accountId to trigger
            return if (resultsList.isNotEmpty()) resultsList.removeAt(0)
            else AccountRefreshResult.Failed(
                accountId,
                RefreshFailure.NetworkFailure("No results")
            )
        }

        override suspend fun refreshAll(
            trigger: RefreshTrigger
        ): RefreshBatchResult {
            refreshAllCalls += trigger
            return resultsList.toList().also { resultsList.clear() }.let(::batch)
        }

        override fun invalidate(accountId: String) {}
    }

    private companion object {
        fun batch(results: List<AccountRefreshResult>) = RefreshBatchResult(
            runId = "test-run",
            results = results,
            aggregate = deriveRefreshBatchAggregate(results)
        )
    }
}
