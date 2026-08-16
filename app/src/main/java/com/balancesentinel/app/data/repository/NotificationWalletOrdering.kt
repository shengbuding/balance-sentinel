package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity

/** Shared ordering semantics for real wallet rows plus the virtual total row. */
internal const val NOTIFICATION_TOTAL_KEY = "__total__"

internal data class NotificationWalletReorderResult(
    val selections: List<NotificationWalletSelectionEntity>,
    val totalDisplayOrder: Int
)

internal fun notificationWalletDisplayPosition(
    selectionIndex: Int,
    totalDisplayOrder: Int,
    showTotal: Boolean
): Int {
    if (selectionIndex < 0) return -1
    if (!showTotal) return selectionIndex
    return if (selectionIndex < totalDisplayOrder) selectionIndex else selectionIndex + 1
}

internal fun notificationWalletOrder(
    selections: List<NotificationWalletSelectionEntity>,
    showTotal: Boolean,
    totalDisplayOrder: Int
): List<String> {
    val walletKeys = selections.map { "${it.accountId}_${it.currency}" }
    if (!showTotal) return walletKeys
    return walletKeys.toMutableList().apply {
        add(totalDisplayOrder.coerceIn(0, walletKeys.size), NOTIFICATION_TOTAL_KEY)
    }
}

internal fun reorderNotificationWallets(
    selections: List<NotificationWalletSelectionEntity>,
    showTotal: Boolean,
    totalDisplayOrder: Int,
    accountId: String?,
    currency: String = "",
    direction: Int
): NotificationWalletReorderResult {
    if (direction == 0) {
        return NotificationWalletReorderResult(
            selections = selections.mapIndexed { index, value -> value.copy(displayOrder = index) },
            totalDisplayOrder = totalDisplayOrder.coerceIn(0, selections.size)
        )
    }

    val items = buildList<OrderItem> {
        selections.forEach { add(OrderItem.Wallet(it)) }
        if (showTotal) add(OrderItem.Total)
    }.toMutableList()
    if (showTotal) {
        val currentTotalIndex = items.lastIndex
        val requestedTotalIndex = totalDisplayOrder.coerceIn(0, selections.size)
        val total = items.removeAt(currentTotalIndex)
        items.add(requestedTotalIndex, total)
    }

    val index = items.indexOfFirst { item ->
        when {
            accountId == null -> item is OrderItem.Total
            item is OrderItem.Wallet ->
                item.value.accountId == accountId && item.value.currency.equals(currency, ignoreCase = true)
            else -> false
        }
    }
    if (index < 0) {
        return NotificationWalletReorderResult(
            selections = selections.mapIndexed { order, value -> value.copy(displayOrder = order) },
            totalDisplayOrder = totalDisplayOrder.coerceIn(0, selections.size)
        )
    }
    val target = (index + direction).coerceIn(0, items.lastIndex)
    if (target != index) {
        val item = items.removeAt(index)
        items.add(target, item)
    }

    return NotificationWalletReorderResult(
        selections = items.filterIsInstance<OrderItem.Wallet>().mapIndexed { order, item ->
            item.value.copy(displayOrder = order)
        },
        totalDisplayOrder = items.indexOfFirst { it is OrderItem.Total }
            .takeIf { it >= 0 }
            ?: totalDisplayOrder.coerceIn(0, selections.size)
    )
}

private sealed interface OrderItem {
    data object Total : OrderItem
    data class Wallet(val value: NotificationWalletSelectionEntity) : OrderItem
}
