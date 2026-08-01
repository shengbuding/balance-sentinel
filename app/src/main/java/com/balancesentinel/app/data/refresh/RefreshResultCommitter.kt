package com.balancesentinel.app.data.refresh

import android.content.Context
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.model.AccountInfo

fun interface RefreshAlertDispatcher {
    fun check(account: AccountInfo, balance: BalanceEntry)
}

fun interface WidgetRedrawNotifier {
    fun notifyRedraw()
}

class RefreshResultCommitter(
    private val context: Context,
    private val accountStore: RefreshAccountStore,
    private val providerCache: ProviderCache = ProviderCache(context),
    private val alertDispatcher: RefreshAlertDispatcher = RefreshAlertDispatcher { _, _ -> },
    private val widgetRedrawNotifier: WidgetRedrawNotifier = WidgetRedrawNotifier { }
) : RefreshCommitter {
    override fun commit(
        request: RefreshRequest,
        fetched: BalanceFetchResult.Success,
        isLatest: () -> Boolean
    ): AccountRefreshResult = AccountRefreshResult.Failed(
        request.accountId,
        RefreshFailure.PersistenceFailure("Refresh persistence is unavailable")
    )
}
