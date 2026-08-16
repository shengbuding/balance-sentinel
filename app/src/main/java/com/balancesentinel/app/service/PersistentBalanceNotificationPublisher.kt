package com.balancesentinel.app.service

import android.content.Context
import com.balancesentinel.app.R
import com.balancesentinel.app.data.refresh.AccountStoreRead
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.data.repository.notificationWalletOrder
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.widget.BalanceWidgetDataStore

/** Publishes the retained balance notification from committed cache data. */
internal class PersistentBalanceNotificationPublisher(
    context: Context,
    private val settingsRepository: SettingsRepository = SettingsRepositoryProvider.get(context),
    private val notificationHelper: NotificationHelper = NotificationHelper(context),
    private val committedBalanceReader: () -> List<AccountBalance> = {
        BalanceWidgetDataStore.getAllBalances(context.applicationContext)
    }
) {
    private val appContext = context.applicationContext

    suspend fun publish(committedBalances: List<AccountBalance>): Boolean {
        val settings = (settingsRepository.snapshot.value as? SettingsSnapshotState.Ready)?.value
            ?: settingsRepository.readSnapshot()
        val showTotal = settings.appSettings.showTotalBalanceInNotification
        val notification = BalanceNotificationDeriver.derive(
            committedBalances = committedBalances,
            walletOrder = notificationWalletOrder(
                selections = settings.notificationSelections,
                showTotal = showTotal,
                totalDisplayOrder = settings.appSettings.notificationTotalDisplayOrder
            ),
            showTotal = showTotal
        )
        if (notification == null) {
            notificationHelper.sendForegroundNotification(
                "--",
                appContext.getString(R.string.service_notif_no_data)
            )
            return false
        }

        val status = if (notification.isAvailable) {
            appContext.getString(R.string.service_notif_status_available)
        } else {
            appContext.getString(R.string.service_notif_status_partial)
        }
        notificationHelper.sendBalanceNotification(
            notification.totalBalance,
            notification.totalCurrency,
            status,
            notification.wallets,
            notification.showTotal,
            notification.totalPosition,
            notification.totalBalance2,
            notification.totalCurrency2
        )
        return true
    }

    suspend fun publishCached(gateway: RefreshGateway): Boolean {
        val activeIds = when (val snapshot = gateway.readAccountSnapshot()) {
            is AccountStoreRead.Ready -> snapshot.accounts.mapTo(mutableSetOf()) { it.id }
            AccountStoreRead.Missing, is AccountStoreRead.Corrupt -> return false
        }
        return publish(committedBalanceReader().filter { it.accountId in activeIds })
    }

    companion object {
        fun from(context: Context): PersistentBalanceNotificationPublisher =
            PersistentBalanceNotificationPublisher(context.applicationContext)
    }
}
