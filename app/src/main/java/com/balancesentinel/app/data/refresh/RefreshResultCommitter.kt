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
import com.balancesentinel.app.data.repository.DataMutationCoordinator
import com.balancesentinel.app.data.repository.RoomRefreshPersistence
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.StaticWidgetProvider_2x1
import com.balancesentinel.app.widget.StaticWidgetProvider_2x2
import com.balancesentinel.app.widget.StaticWidgetProvider_3x1
import com.balancesentinel.app.widget.StaticWidgetProvider_4x2
import com.balancesentinel.app.widget.StaticWidgetProvider_5x1
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

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
    private val widgetRedrawNotifier: WidgetRedrawNotifier = AndroidWidgetRedrawNotifier(context),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val roomPersistence: RoomRefreshPersistence =
        RoomRefreshPersistence(WalletDatabaseProvider.get(context)),
    private val afterPersistenceWrite: () -> Unit = {},
    private val runRecorder: RefreshRunRecorder? = null
) : RefreshCommitter {

    override val recordsRunOutcome: Boolean = runRecorder != null

    override suspend fun commit(
        request: RefreshRequest,
        fetched: BalanceFetchResult.Success,
        isLatest: () -> Boolean
    ): AccountRefreshResult = RefreshMutationBarrier.withRefreshCommitSuspend {
        DataMutationCoordinator.withMutationSuspend {
        if (!isLatest()) return@withMutationSuspend stale(request.accountId)
        val account = accountStore.getAccount(request.accountId)
        if (
            account == null ||
            account.id != request.accountId ||
            account.revision != request.revision
        ) {
            return@withMutationSuspend stale(request.accountId)
        }
        if (fetched.balance.balances.any { !it.hasPersistableAmounts() }) {
            return@withMutationSuspend responseSchemaFailure(request.accountId)
        }

        try {
            val currentAccount = accountStore.getAccount(request.accountId)
            if (
                !isLatest() ||
                currentAccount == null ||
                currentAccount.id != request.accountId ||
                currentAccount.revision != request.revision
            ) {
                return@withMutationSuspend stale(request.accountId)
            }

            try {
                val rawTimestamp = wallClock()
                val rawBatch = fetched.balance.balances.map { entry ->
                    entry.toRawRecord(account.id, rawTimestamp)
                }
                val logs = fetched.balance.balances.map { entry ->
                    entry.toRefreshLog(request.trigger, account, fetched.balance.isAvailable, fetched.completedAt)
                }
                val usage = UsageSnapshot(account.id, fetched.completedAt, records = emptyList())
                val committed = AccountRefreshResult.Committed(
                    request.accountId,
                    fetched.balance,
                    fetched.completedAt
                )
                val persistedResult = if (runRecorder != null && request.runId != null) {
                    runRecorder.recordAccount(request.runId, request, committed) {
                        roomPersistence.commit(
                            rawBatch,
                            listOf(usage),
                            logs,
                            "refresh:${fetched.completedAt}",
                            account.id
                        )
                    }
                } else {
                    roomPersistence.commit(
                        rawBatch,
                        listOf(usage),
                        logs,
                        "refresh:${fetched.completedAt}",
                        account.id
                    )
                    committed
                }

                if (persistedResult !is AccountRefreshResult.Committed) {
                    return@withMutationSuspend persistedResult
                }

                // Cache/widget state is published only after the durable Room transaction succeeds.
                providerCache.put(account.providerType, account.id, fetched.balance)
                BalanceWidgetDataStore.replaceAccountBalances(
                    context,
                    account.id,
                    fetched.balance.balances.map { entry ->
                        entry.toWidgetBalance(account, fetched.balance.isAvailable, fetched.completedAt)
                    }
                )

                afterPersistenceWrite()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withMutationSuspend persistenceFailure(request.accountId)
            }

            fetched.balance.balances.forEach { entry ->
                runCatching { alertDispatcher.check(account, entry) }
            }
            runCatching { widgetRedrawNotifier.notifyRedraw() }
            AccountRefreshResult.Committed(request.accountId, fetched.balance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            persistenceFailure(request.accountId)
        }
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

    private fun BalanceEntry.toRawRecord(accountId: String, timestamp: Long) = RawRecord(
        accountId = accountId,
        timestamp = timestamp,
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

    private fun responseSchemaFailure(accountId: String) = AccountRefreshResult.Failed(
        accountId,
        RefreshFailure.ResponseSchemaFailure("Balance response schema is invalid")
    )

    private companion object {
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
        AlertChecker.checkPublished(context, account.id, amount, balance.currency, account.label)
        AlertChecker.checkChangePublished(context, account.id, amount, balance.currency, account.label)
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
