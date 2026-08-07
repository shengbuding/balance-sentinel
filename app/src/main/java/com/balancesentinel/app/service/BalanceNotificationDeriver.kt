package com.balancesentinel.app.service

import com.balancesentinel.app.data.repository.AlertIdentity
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.widget.BalanceWidgetDataStore

internal data class ServiceBalanceNotification(
    val totalBalance: String,
    val totalCurrency: String,
    val totalBalance2: String = "",
    val totalCurrency2: String = "",
    val isAvailable: Boolean,
    val wallets: List<AccountBalance>,
    val showTotal: Boolean,
    val totalPosition: Int
)

internal object BalanceNotificationDeriver {
    private const val NOTIFICATION_TOTAL_KEY = "__total__"

    fun derive(
        committedBalances: List<AccountBalance>,
        walletOrder: List<String>,
        showTotal: Boolean
    ): ServiceBalanceNotification? {
        val aggregate = BalanceWidgetDataStore.aggregateTopTwo(committedBalances) ?: return null
        val positions = walletOrder.withIndex().associate { it.value to it.index }
        val selectedWallets = committedBalances.filter { balance ->
            AlertIdentity(balance.accountId, balance.currency).storageSuffix in walletOrder
        }.sortedBy { balance ->
            positions.getValue(AlertIdentity(balance.accountId, balance.currency).storageSuffix)
        }
        return ServiceBalanceNotification(
            totalBalance = aggregate.totalBalance,
            totalCurrency = aggregate.currency,
            totalBalance2 = aggregate.totalBalance2,
            totalCurrency2 = aggregate.currency2,
            isAvailable = aggregate.isAvailable,
            wallets = selectedWallets,
            showTotal = showTotal,
            totalPosition = if (showTotal) {
                walletOrder.indexOf(NOTIFICATION_TOTAL_KEY)
            } else {
                -1
            }
        )
    }
}
