package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.widget.BalanceWidgetDataStore

class AccountLifecycleManager(
    private val context: Context,
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(context)
) {
    private val widgetPrefs = WidgetPrefs(context)

    fun delete(accountId: String) {
        val account = apiKeyManager.getAccount(accountId) ?: return

        apiKeyManager.removeAccount(accountId)
        RawRecordStore.removeByAccountId(context, accountId)
        DailySummaryStore.removeByAccountId(context, accountId)
        UsageDataStore.removeByAccountId(context, accountId)
        BalanceWidgetDataStore.removeAccountBalance(context, accountId)
        ProviderCache(context).clear(account.providerType, accountId)
        widgetPrefs.removeAccountData(accountId)
        ApiDebugStore.clearEntries(accountId)
    }
}
