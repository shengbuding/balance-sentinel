package com.balancesentinel.app.widget

import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshTrigger

/**
 * Extracted widget refresh logic that routes through the shared [RefreshGateway].
 * Replaces direct DeepSeekApiService / ProviderFactory instantiation in StaticWidgetProvider.
 *
 * Calls [RefreshGateway.refreshAll] once — the gateway's coordinator handles
 * per-account parallelism, credential lookup, and committer persistence.
 * This runner only triggers the refresh; it does not read credentials.
 */
class WidgetRefreshRunner(
    private val gateway: RefreshGateway
) {
    /**
     * Execute a widget refresh through the shared gateway.
     * @param watchdog true for watchdog-triggered refresh, false for button refresh
     */
    suspend fun refreshNow(watchdog: Boolean = false): RefreshBatchResult {
        val trigger = if (watchdog) RefreshTrigger.WATCHDOG else RefreshTrigger.WIDGET
        return gateway.refreshAll(trigger)
    }
}
