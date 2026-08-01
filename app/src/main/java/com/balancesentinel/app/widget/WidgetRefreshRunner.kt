package com.balancesentinel.app.widget

import android.content.Context
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.repository.ApiKeyManager

/**
 * Extracted widget refresh logic that routes through the shared [RefreshGateway].
 * Replaces direct DeepSeekApiService / ProviderFactory instantiation in StaticWidgetProvider.
 *
 * The gateway's committer owns all persistence (Widget cache, raw records,
 * refresh logs, usage snapshots, alerts). This runner only triggers the refresh.
 */
class WidgetRefreshRunner(
    private val context: Context,
    private val apiKeyManager: ApiKeyManager,
    private val gateway: RefreshGateway
) {
    /**
     * Execute a widget refresh through the shared gateway.
     * @param watchdog true for watchdog-triggered refresh, false for button refresh
     */
    suspend fun refreshNow(watchdog: Boolean = false) {
        val accounts = apiKeyManager.getAccounts()
        if (accounts.isEmpty()) return

        val trigger = if (watchdog) RefreshTrigger.WATCHDOG else RefreshTrigger.WIDGET
        for (account in accounts) {
            gateway.refreshAccount(account.id, trigger)
        }
    }
}
