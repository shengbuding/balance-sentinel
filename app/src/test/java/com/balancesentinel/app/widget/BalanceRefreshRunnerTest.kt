package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshBatchState
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests that [WidgetRefreshRunner] routes through the shared [RefreshGateway]
 * via a single [RefreshGateway.refreshAll] call — not per-account
 * [RefreshGateway.refreshAccount]. The gateway's coordinator handles
 * per-account parallelism and credential lookup; the runner only triggers.
 */
@RunWith(RobolectricTestRunner::class)
class BalanceRefreshRunnerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BalanceWidgetDataStore.clearAll(context)
    }

    @After
    fun tearDown() {
        BalanceWidgetDataStore.clearAll(context)
    }

    // The runner must call refreshAll once — the gateway handles per-account routing.
    @Test
    fun `widget refresh calls refreshAll once with WIDGET trigger`() = runTest {
        val gateway = DistinguishingRefreshGateway(
            committed("acct-1", 8.0, "CNY")
        )

        WidgetRefreshRunner(gateway).refreshNow()

        assertEquals("refreshAll must be called exactly once", 1, gateway.refreshAllCalls.size)
        assertEquals(RefreshTrigger.WIDGET, gateway.refreshAllCalls[0])
        assertTrue("refreshAccount must not be called directly", gateway.refreshAccountCalls.isEmpty())
    }

    // Watchdog trigger must route through refreshAll with WATCHDOG.
    @Test
    fun `watchdog refresh calls refreshAll once with WATCHDOG trigger`() = runTest {
        val gateway = DistinguishingRefreshGateway(
            committed("acct-2", 5.0, "CNY")
        )

        WidgetRefreshRunner(gateway).refreshNow(watchdog = true)

        assertEquals("refreshAll must be called exactly once", 1, gateway.refreshAllCalls.size)
        assertEquals(RefreshTrigger.WATCHDOG, gateway.refreshAllCalls[0])
        assertTrue("refreshAccount must not be called directly", gateway.refreshAccountCalls.isEmpty())
    }

    // Empty accounts is the gateway's responsibility — the runner always calls refreshAll.
    @Test
    fun `widget refresh always calls refreshAll even with empty accounts`() = runTest {
        val gateway = DistinguishingRefreshGateway()

        WidgetRefreshRunner(gateway).refreshNow()

        assertEquals("refreshAll must be called even when no accounts exist", 1, gateway.refreshAllCalls.size)
        assertEquals(RefreshTrigger.WIDGET, gateway.refreshAllCalls[0])
    }

    @Test
    fun `widget refresh returns the real batch aggregate`() = runTest {
        val gateway = DistinguishingRefreshGateway(
            AccountRefreshResult.Failed("acct-1", RefreshFailure.AuthenticationFailure("bad key"))
        )

        val result = WidgetRefreshRunner(gateway).refreshNow() as Any

        assertEquals(RefreshBatchResult::class.java, result::class.java)
        assertEquals("acct-1", (result as RefreshBatchResult).results.single().accountId)
        assertEquals(RefreshBatchState.FAILED, result.aggregate.state)
    }

    private fun committed(accountId: String, amount: Double, currency: String) =
        AccountRefreshResult.Committed(
            accountId,
            UnifiedBalance(
                provider = ProviderType.DEEPSEEK,
                accountId = accountId,
                isAvailable = true,
                balances = listOf(BalanceEntry(currency, amount))
            )
        )

    /**
     * Distinguishes refreshAll from refreshAccount calls.
     * Tracks both paths separately so tests can assert the runner
     * uses refreshAll and never calls refreshAccount directly.
     */
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
            return resultsList.toList().also { resultsList.clear() }.let { results ->
                RefreshBatchResult("test-run", results, deriveRefreshBatchAggregate(results))
            }
        }

        override fun invalidate(accountId: String) {}
    }
}
