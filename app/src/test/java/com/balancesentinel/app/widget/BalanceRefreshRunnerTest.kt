package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceRefreshRunnerTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var apiKeyManager: ApiKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "test_runner_${System.nanoTime()}"
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

    // Mutation caught: widget refresh bypassing the shared gateway and calling
    // DeepSeekApiService/ProviderFactory directly per account.
    @Test
    fun `widget refresh uses account provider through shared gateway`() = runTest {
        val account = apiKeyManager.addAccount(
            label = "Widget account",
            apiKey = "widget-key-12345",
            providerType = ProviderType.MODEL_ARK
        )
        val gateway = RecordingRefreshGateway(
            committed(account.id, 8.0, "Token")
        )

        WidgetRefreshRunner(context, apiKeyManager, gateway).refreshNow()

        assertEquals(
            listOf(account.id to RefreshTrigger.WIDGET),
            gateway.calls
        )
        assertTrue(
            "Widget store should not contain direct-API CNY residue",
            BalanceWidgetDataStore.getAllBalances(context).none { it.currency == "CNY" }
        )
    }

    // Mutation caught: widget refresh with watchdog trigger not routed through gateway.
    @Test
    fun `watchdog refresh routes through gateway with WATCHDOG trigger`() = runTest {
        val account = apiKeyManager.addAccount(
            label = "Watchdog account",
            apiKey = "watchdog-key-12345",
            providerType = ProviderType.DEEPSEEK
        )
        val gateway = RecordingRefreshGateway(
            committed(account.id, 5.0, "CNY")
        )

        WidgetRefreshRunner(context, apiKeyManager, gateway).refreshNow(watchdog = true)

        assertEquals(
            listOf(account.id to RefreshTrigger.WATCHDOG),
            gateway.calls
        )
    }

    // Mutation caught: widget instantiating DeepSeekApiService or reading credentials
    // when all accounts are empty.
    @Test
    fun `widget refresh with no accounts does not call gateway`() = runTest {
        val gateway = RecordingRefreshGateway()

        WidgetRefreshRunner(context, apiKeyManager, gateway).refreshNow()

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
        private val iterator = results.iterator()

        override suspend fun refreshAccount(
            accountId: String,
            trigger: RefreshTrigger
        ): AccountRefreshResult {
            calls += accountId to trigger
            return if (iterator.hasNext()) {
                iterator.next()
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
            // Delegate per-account for recording
            return listOf(refreshAccount(calls.lastOrNull()?.first ?: "unknown", trigger))
        }

        override fun invalidate(accountId: String) {}
    }
}
