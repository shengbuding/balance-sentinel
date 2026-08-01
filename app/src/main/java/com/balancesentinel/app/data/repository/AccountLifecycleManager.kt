package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.widget.BalanceWidgetDataStore

class AccountLifecycleManager(
    context: Context,
    private val accountManager: ApiKeyManager = ApiKeyManager(context),
    private val widgetPrefs: WidgetPrefs = WidgetPrefs(context),
    private val providerCache: ProviderCache = ProviderCache.getInstance(context)
) {
    private val appContext = context.applicationContext

    fun save(existingId: String?, draft: AccountDraft): AccountSaveResult {
        val result = accountManager.saveAccount(existingId, draft)
        if (result is AccountSaveResult.Replaced) {
            val migration = mapOf(result.before.id to result.account.id)
            RawRecordStore.migrateAccountIds(appContext, migration)
            DailySummaryStore.migrateAccountIds(appContext, migration)
            UsageDataStore.migrateAccountIds(appContext, migration)
            widgetPrefs.migrateAccountId(result.before.id, result.account.id)
            BalanceWidgetDataStore.removeAccountBalance(appContext, result.before.id)
            providerCache.clear(result.before.providerType, result.before.id)
            ApiDebugStore.clearEntries(result.before.id)
        }
        return result
    }

    fun delete(accountId: String) {
        val account = accountManager.getAccount(accountId)
        accountManager.removeAccount(accountId)
        RawRecordStore.removeByAccountId(appContext, accountId)
        DailySummaryStore.removeByAccountId(appContext, accountId)
        UsageDataStore.removeByAccountId(appContext, accountId)
        widgetPrefs.removeAccountAlertState(accountId)
        BalanceWidgetDataStore.removeAccountBalance(appContext, accountId)
        account?.let { providerCache.clear(it.providerType, accountId) }
        ApiDebugStore.clearEntries(accountId)
    }
}
