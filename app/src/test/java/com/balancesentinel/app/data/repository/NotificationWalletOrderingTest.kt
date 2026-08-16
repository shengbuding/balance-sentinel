package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationWalletOrderingTest {
    private val first = NotificationWalletSelectionEntity("account-a", "CNY", 0)
    private val second = NotificationWalletSelectionEntity("account-b", "USD", 1)

    @Test
    fun `total row is inserted at persisted display order`() {
        assertEquals(
            listOf("account-a_CNY", NOTIFICATION_TOTAL_KEY, "account-b_USD"),
            notificationWalletOrder(listOf(first, second), true, 1)
        )
    }

    @Test
    fun `moving total updates only its virtual position`() {
        val result = reorderNotificationWallets(
            selections = listOf(first, second),
            showTotal = true,
            totalDisplayOrder = 0,
            accountId = null,
            direction = 1
        )

        assertEquals(listOf(first, second), result.selections)
        assertEquals(1, result.totalDisplayOrder)
    }

    @Test
    fun `moving wallet across total preserves wallet order and moves total`() {
        val result = reorderNotificationWallets(
            selections = listOf(first, second),
            showTotal = true,
            totalDisplayOrder = 1,
            accountId = second.accountId,
            currency = second.currency,
            direction = -1
        )

        assertEquals(listOf(first, second), result.selections)
        assertEquals(2, result.totalDisplayOrder)
        assertEquals(
            listOf("account-a_CNY", "account-b_USD", NOTIFICATION_TOTAL_KEY),
            notificationWalletOrder(result.selections, true, result.totalDisplayOrder)
        )
    }

    @Test
    fun `wallet display positions account for total in the middle`() {
        assertEquals(0, notificationWalletDisplayPosition(0, 1, true))
        assertEquals(2, notificationWalletDisplayPosition(1, 1, true))
    }
}
