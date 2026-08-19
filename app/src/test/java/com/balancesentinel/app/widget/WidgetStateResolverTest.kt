package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.PERCENTAGE_CURRENCY
import com.balancesentinel.app.data.api.QuotaPeriodSnapshot
import com.balancesentinel.app.data.api.QuotaSnapshot
import com.balancesentinel.app.data.refresh.RefreshBatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateResolverTest {
    private val base = WidgetStateInput(
        config = WidgetConfig("account-1", "USD"),
        activeAccounts = mapOf("account-1" to "Primary"),
        balances = listOf(balance()),
        lastRefresh = null,
        capabilityRestricted = false
    )

    @Test
    fun `table of widget states preserves the six user visible states`() {
        assertTrue(WidgetStateResolver.resolve(base.copy(config = null)) is WidgetViewState.Unconfigured)
        assertTrue(
            WidgetStateResolver.resolve(base.copy(config = WidgetConfig("deleted", "USD")))
                is WidgetViewState.Unconfigured
        )
        assertTrue(WidgetStateResolver.resolve(base.copy(balances = emptyList())) is WidgetViewState.NoData)
        assertTrue(WidgetStateResolver.resolve(base) is WidgetViewState.Fresh)
        assertTrue(
            WidgetStateResolver.resolve(
                base.copy(
                    balances = listOf(balance(stale = true)),
                    lastRefresh = failedStatus()
                )
            ) is WidgetViewState.Stale
        )
        assertTrue(
            WidgetStateResolver.resolve(base.copy(capabilityRestricted = true))
                is WidgetViewState.PermissionRestricted
        )
        assertTrue(
            WidgetStateResolver.resolve(base.copy(balances = emptyList(), lastRefresh = failedStatus()))
                is WidgetViewState.RefreshFailed
        )
    }

    @Test
    fun `permission restricted state never becomes fresh even with available cached data`() {
        val state = WidgetStateResolver.resolve(base.copy(capabilityRestricted = true))
        assertTrue(state is WidgetViewState.PermissionRestricted)
        assertEquals("account-1", state.selection.accountId)
    }

    @Test
    fun `subscription account uses quota aggregation instead of money aggregation`() {
        val state = WidgetStateResolver.resolve(
            base.copy(
                config = WidgetConfig("account-1", PERCENTAGE_CURRENCY),
                balances = listOf(
                    AccountBalance(
                        accountId = "account-1",
                        label = "Primary",
                        totalBalance = "83",
                        currency = PERCENTAGE_CURRENCY,
                        isAvailable = true,
                        grantedBalance = "",
                        toppedUpBalance = "",
                        lastUpdated = 2_000L,
                        quota = QuotaSnapshot(
                            listOf(
                                QuotaPeriodSnapshot("rolling_5h", 10.0, 90.0),
                                QuotaPeriodSnapshot("monthly", 17.0, 83.0),
                                QuotaPeriodSnapshot("weekly", 25.0, 75.0)
                            )
                        )
                    )
                )
            )
        )

        assertTrue(state is WidgetViewState.Fresh)
        val balance = (state as WidgetViewState.Fresh).balance
        assertEquals(PERCENTAGE_CURRENCY, balance.currency)
        assertEquals("10.0", balance.totalBalance)
        assertEquals(3, balance.quota?.periods?.size)
    }

    @Test
    fun `subscription account honors selected quota period`() {
        val quota = QuotaSnapshot(
            listOf(
                QuotaPeriodSnapshot("rolling_5h", 10.0, 90.0),
                QuotaPeriodSnapshot("weekly", 25.0, 75.0),
                QuotaPeriodSnapshot("monthly", 17.0, 83.0)
            )
        )
        val subscription = AccountBalance(
            accountId = "account-1",
            label = "Primary",
            totalBalance = "83",
            currency = PERCENTAGE_CURRENCY,
            isAvailable = true,
            grantedBalance = "",
            toppedUpBalance = "",
            lastUpdated = 2_000L,
            quota = quota
        )

        mapOf("rolling_5h" to "10.0", "weekly" to "25.0", "monthly" to "17.0")
            .forEach { (period, expected) ->
                val state = WidgetStateResolver.resolve(
                    base.copy(
                        config = WidgetConfig("account-1", PERCENTAGE_CURRENCY, period),
                        balances = listOf(subscription)
                    )
                )
                assertTrue(state is WidgetViewState.Fresh)
                state as WidgetViewState.Fresh
                assertEquals(period, state.selection.quotaPeriod)
                assertEquals(expected, state.balance.totalBalance)
            }
    }

    private fun balance(stale: Boolean = false) = AccountBalance(
        accountId = "account-1",
        label = "Primary",
        totalBalance = "12.50",
        currency = "USD",
        isAvailable = true,
        grantedBalance = "1.00",
        toppedUpBalance = "2.00",
        lastUpdated = 1_800_000_000_000L,
        stale = stale
    )

    private fun failedStatus() = WidgetRefreshStatus(
        runId = "run-1",
        state = RefreshBatchState.FAILED,
        accountCount = 1,
        successCount = 0,
        failureCount = 1,
        cancelledCount = 0
    )
}
