package com.balancesentinel.app.service

import com.balancesentinel.app.data.repository.AlertIdentity
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.widget.AccountBalance

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
    fun derive(
        committedBalances: List<AccountBalance>,
        walletOrder: List<String>,
        showTotal: Boolean
    ): ServiceBalanceNotification? {
        val primary = committedBalances.maxByOrNull {
            it.totalBalance.toDoubleOrNull() ?: 0.0
        } ?: return null
        val selectedWallets = committedBalances.filter { balance ->
            AlertIdentity(balance.accountId, balance.currency).storageSuffix in walletOrder
        }
        return ServiceBalanceNotification(
            totalBalance = primary.totalBalance,
            totalCurrency = primary.currency,
            isAvailable = committedBalances.all(AccountBalance::isAvailable),
            wallets = selectedWallets,
            showTotal = showTotal,
            totalPosition = if (showTotal) {
                walletOrder.indexOf(WidgetPrefs.KEY_NOTIFICATION_TOTAL)
            } else {
                -1
            }
        )
    }
}
