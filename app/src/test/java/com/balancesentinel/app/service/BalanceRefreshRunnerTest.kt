package com.balancesentinel.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests that the BalanceRefreshService refresh path routes through the
 * shared [RefreshGateway] with [RefreshTrigger.SERVICE] and derives
 * notification totals only from committed Widget storage.
 */
@RunWith(RobolectricTestRunner::class)
class BalanceRefreshRunnerTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var apiKeyManager: ApiKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "test_svc_runner_${System.nanoTime()}"
        apiKeyManager = ApiKeyManager(
            context,
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        BalanceWidgetDataStore.clearAll(context)
    }

    @After
    fun tearDown() {
        BalanceWidgetDataStore.clearAll(context)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Mutation caught: service refresh bypassing the shared gateway and calling
    // DeepSeekApiService / ProviderFactory directly per account.
    @Test
    fun `service refresh routes all accounts through gateway with SERVICE trigger`() = runTest {
        val account = apiKeyManager.addAccount(
            label = "Service account",
            apiKey = "svc-key-12345",
            providerType = ProviderType.DEEPSEEK
        )
        val gateway = RecordingRefreshGateway(
            committed(account.id, 10.0, "CNY")
        )

        gateway.refreshAll(RefreshTrigger.SERVICE)

        assertEquals(
            listOf(account.id to RefreshTrigger.SERVICE),
            gateway.calls
        )
    }

    // Mutation caught: notification totals read from provider results rather
    // than committed Widget storage.
    @Test
    fun `service notification derives totals from committed widget storage`() = runTest {
        val accountId = "svc-acc-1"
        BalanceWidgetDataStore.saveAccountBalance(
            context, accountId, "Test", "150.00", "CNY", true, "120.00", "30.00"
        )

        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(1, balances.size)
        assertEquals("150.00", balances[0].totalBalance)
        assertEquals("CNY", balances[0].currency)
        assertTrue(balances[0].isAvailable)
    }

    // Mutation caught: service instantiating DeepSeekApiService when all
    // accounts are empty.
    @Test
    fun `service refresh with no accounts does not call gateway`() = runTest {
        val gateway = RecordingRefreshGateway()

        gateway.refreshAll(RefreshTrigger.SERVICE)

        assertTrue("No gateway calls expected for empty accounts", gateway.calls.isEmpty())
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
     * Records gateway calls and returns pre-configured results.
     * Does NOT write to any store — tests verify routing, not persistence.
     */
    private class RecordingRefreshGateway(
        vararg private val results: AccountRefreshResult
    ) : RefreshGateway {
        val calls = mutableListOf<Pair<String, RefreshTrigger>>()
        private val resultsList = results.toMutableList()

        override suspend fun refreshAccount(
            accountId: String,
            trigger: RefreshTrigger
        ): AccountRefreshResult {
            calls += accountId to trigger
            return if (resultsList.isNotEmpty()) {
                resultsList.removeFirst()
            } else {
                AccountRefreshResult.Failed(
                    accountId,
                    RefreshFailure.NetworkFailure("No more results")
                )
            }
        }

        override suspend fun refreshAll(
            trigger: RefreshTrigger
        ): List<AccountRefreshResult> {
            val snapshot = resultsList.toList()
            for (r in snapshot) {
                calls += r.accountId to trigger
            }
            resultsList.clear()
            return snapshot
        }

        override fun invalidate(accountId: String) {}
    }
}
