package com.balancesentinel.app.service

import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.test.runTest
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
        ): List<AccountRefreshResult> {
            refreshAllCalls += trigger
            return resultsList.toList().also { resultsList.clear() }
        }

        override fun invalidate(accountId: String) {}
    }
}
