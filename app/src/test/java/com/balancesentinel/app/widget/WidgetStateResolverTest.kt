package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
