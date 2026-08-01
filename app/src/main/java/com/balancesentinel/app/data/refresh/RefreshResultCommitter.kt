package com.balancesentinel.app.data.refresh

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.AlertChecker
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.UsageDataStore
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.StaticWidgetProvider_2x1
import com.balancesentinel.app.widget.StaticWidgetProvider_2x2
import com.balancesentinel.app.widget.StaticWidgetProvider_3x1
import com.balancesentinel.app.widget.StaticWidgetProvider_4x2
import com.balancesentinel.app.widget.StaticWidgetProvider_5x1
import java.util.concurrent.atomic.AtomicLong

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
    private val alertDispatcher: RefreshAlertDispatcher = AndroidRefreshAlertDispatcher(context),
    private val widgetRedrawNotifier: WidgetRedrawNotifier = AndroidWidgetRedrawNotifier(context)
) : RefreshCommitter {

    override fun commit(
        request: RefreshRequest,
        fetched: BalanceFetchResult.Success,
        isLatest: () -> Boolean
    ): AccountRefreshResult = synchronized(COMMIT_LOCK) {
        if (!isLatest()) return@synchronized stale(request.accountId)
        val account = accountStore.getAccount(request.accountId)
        if (
            account == null ||
            account.id != request.accountId ||
            account.revision != request.revision
        ) {
            return@synchronized stale(request.accountId)
        }

        try {
            val providerBefore = providerCache.snapshot(account.providerType, account.id)
            val widgetBefore = BalanceWidgetDataStore.snapshotAccountBalances(context, account.id)
            val recordsBefore = RawRecordStore.snapshotRecords(context)
            val logsBefore = RefreshLogStore.snapshotEntries(context)
            val usageBefore = UsageDataStore.snapshotAll(context)

            val currentAccount = accountStore.getAccount(request.accountId)
            if (
                !isLatest() ||
                currentAccount == null ||
                currentAccount.id != request.accountId ||
                currentAccount.revision != request.revision
            ) {
                return@synchronized stale(request.accountId)
            }

            var attemptedStage = 0
            try {
                attemptedStage = 1
                providerCache.put(account.providerType, account.id, fetched.balance)

                attemptedStage = 2
                BalanceWidgetDataStore.replaceAccountBalances(
                    context,
                    account.id,
                    fetched.balance.balances.map { entry ->
                        entry.toWidgetBalance(account, fetched.balance.isAvailable, fetched.completedAt)
                    }
                )

                attemptedStage = 3
                RawRecordStore.addRecordsStrict(
                    context,
                    fetched.balance.balances.map { entry ->
                        entry.toRawRecord(account.id, fetched.completedAt)
                    }
                )

                attemptedStage = 4
                RefreshLogStore.addEntriesStrict(
                    context,
                    fetched.balance.balances.map { entry ->
                        entry.toRefreshLog(request.trigger, account, fetched.balance.isAvailable, fetched.completedAt)
                    }
                )

                attemptedStage = 5
                UsageDataStore.saveSnapshot(
                    context,
                    UsageSnapshot(account.id, fetched.completedAt, records = emptyList())
                )
            } catch (_: Exception) {
                rollback(
                    attemptedStage = attemptedStage,
                    account = account,
                    providerBefore = providerBefore,
                    widgetBefore = widgetBefore,
                    recordsBefore = recordsBefore,
                    logsBefore = logsBefore,
                    usageBefore = usageBefore
                )
                return@synchronized persistenceFailure(request.accountId)
            }

            fetched.balance.balances.forEach { entry ->
                runCatching { alertDispatcher.check(account, entry) }
            }
            runCatching { widgetRedrawNotifier.notifyRedraw() }
            AccountRefreshResult.Committed(request.accountId, fetched.balance)
        } catch (_: Exception) {
            persistenceFailure(request.accountId)
        }
    }

    private fun rollback(
        attemptedStage: Int,
        account: AccountInfo,
        providerBefore: ProviderCache.CachedBalance?,
        widgetBefore: List<AccountBalance>,
        recordsBefore: List<RawRecord>,
        logsBefore: List<RefreshLogEntry>,
        usageBefore: List<UsageSnapshot>
    ) {
        if (attemptedStage >= 5) runCatching { UsageDataStore.restoreAll(context, usageBefore) }
        if (attemptedStage >= 4) runCatching { RefreshLogStore.restoreEntries(context, logsBefore) }
        if (attemptedStage >= 3) runCatching { RawRecordStore.restoreRecords(context, recordsBefore) }
        if (attemptedStage >= 2) {
            runCatching {
                BalanceWidgetDataStore.replaceAccountBalances(context, account.id, widgetBefore)
            }
        }
        if (attemptedStage >= 1) {
            runCatching { providerCache.restore(account.providerType, account.id, providerBefore) }
        }
    }

    private fun BalanceEntry.toWidgetBalance(
        account: AccountInfo,
        available: Boolean,
        completedAt: Long
    ) = AccountBalance(
        accountId = account.id,
        label = account.label,
        totalBalance = totalBalance.toString(),
        currency = currency,
        isAvailable = available,
        grantedBalance = grantedBalance?.toString().orEmpty(),
        toppedUpBalance = toppedUpBalance?.toString().orEmpty(),
        lastUpdated = completedAt
    )

    private fun BalanceEntry.toRawRecord(accountId: String, completedAt: Long) = RawRecord(
        accountId = accountId,
        timestamp = completedAt,
        currency = currency,
        totalBalance = totalBalance.toFloat(),
        grantedBalance = grantedBalance?.toFloat() ?: 0f,
        toppedUpBalance = toppedUpBalance?.toFloat() ?: 0f
    )

    private fun BalanceEntry.toRefreshLog(
        trigger: RefreshTrigger,
        account: AccountInfo,
        available: Boolean,
        completedAt: Long
    ) = RefreshLogEntry(
        id = nextLogId(completedAt),
        type = when (trigger) {
            RefreshTrigger.MANUAL_ALL, RefreshTrigger.MANUAL_ACCOUNT -> RefreshLogType.MANUAL
            else -> RefreshLogType.AUTO
        },
        totalBalance = totalBalance.toString(),
        currency = currency,
        isAvailable = available,
        grantedBalance = grantedBalance?.toString().orEmpty(),
        toppedUpBalance = toppedUpBalance?.toString().orEmpty(),
        timestamp = completedAt,
        message = account.label
    )

    private fun stale(accountId: String) = AccountRefreshResult.Stale(
        accountId,
        RefreshFailure.AccountStale("Refresh result is stale")
    )

    private fun persistenceFailure(accountId: String) = AccountRefreshResult.Failed(
        accountId,
        RefreshFailure.PersistenceFailure("Refresh data could not be saved")
    )

    private companion object {
        val COMMIT_LOCK = Any()
        val LOG_ID = AtomicLong(System.currentTimeMillis())

        fun nextLogId(completedAt: Long): Long = LOG_ID.updateAndGet { previous ->
            maxOf(previous + 1, completedAt)
        }
    }
}

private class AndroidRefreshAlertDispatcher(
    private val context: Context
) : RefreshAlertDispatcher {
    override fun check(account: AccountInfo, balance: BalanceEntry) {
        val amount = balance.totalBalance.toString()
        AlertChecker.check(context, account.id, amount, balance.currency, account.label)
        AlertChecker.checkChange(context, account.id, amount, balance.currency, account.label)
    }
}

private class AndroidWidgetRedrawNotifier(context: Context) : WidgetRedrawNotifier {
    private val appContext = context.applicationContext

    override fun notifyRedraw() {
        val manager = AppWidgetManager.getInstance(appContext)
        WIDGET_PROVIDERS.forEach { providerClass ->
            val ids = manager.getAppWidgetIds(ComponentName(appContext, providerClass))
            if (ids.isNotEmpty()) {
                appContext.sendBroadcast(
                    Intent(appContext, providerClass).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }
    }

    private companion object {
        val WIDGET_PROVIDERS = listOf(
            StaticWidgetProvider_2x1::class.java,
            StaticWidgetProvider_2x2::class.java,
            StaticWidgetProvider_3x1::class.java,
            StaticWidgetProvider_4x2::class.java,
            StaticWidgetProvider_5x1::class.java
        )
    }
}
