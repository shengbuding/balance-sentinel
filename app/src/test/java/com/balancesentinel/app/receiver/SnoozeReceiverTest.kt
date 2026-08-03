package com.balancesentinel.app.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.repository.WidgetPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class SnoozeReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
        WidgetPrefs(context).resetAll()
    }

    @Test
    fun `currency snooze cancels only that pair while snoozing the account`() {
        val prefs = WidgetPrefs(context)
        prefs.resetAll()
        val helper = NotificationHelper(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
        helper.sendLowBalanceAlert("acct", 1f, 10f, "CNY", "CNY")
        helper.sendChangeAlert("acct", 1f, 2f, 1f, 15, "CNY", "CNY")
        helper.sendLowBalanceAlert("acct", 1f, 10f, "USD", "USD")
        helper.sendChangeAlert("acct", 1f, 2f, 1f, 15, "USD", "USD")
        val shadow = Shadows.shadowOf(nm)
        assertNotNull(shadow.getNotification(helper.alertNotificationId("acct", "CNY")))
        assertNotNull(shadow.getNotification(helper.changeNotificationId("acct", "CNY")))
        assertNotNull(shadow.getNotification(helper.alertNotificationId("acct", "USD")))
        assertNotNull(shadow.getNotification(helper.changeNotificationId("acct", "USD")))
        val before = System.currentTimeMillis()

        SnoozeReceiver().onReceive(
            context,
            Intent().apply {
                putExtra("account_id", "acct")
                putExtra("currency", "CNY")
            }
        )

        assertTrue(prefs.getSnoozeUntil("acct") >= before + 60 * 60_000L)
        assertNull(shadow.getNotification(helper.alertNotificationId("acct", "CNY")))
        assertNull(shadow.getNotification(helper.changeNotificationId("acct", "CNY")))
        assertNotNull(shadow.getNotification(helper.alertNotificationId("acct", "USD")))
        assertNotNull(shadow.getNotification(helper.changeNotificationId("acct", "USD")))
    }

    @Test
    fun `currency action still snoozes its intended account with account wide storage`() {
        val prefs = WidgetPrefs(context)
        prefs.resetAll()
        val receiver = SnoozeReceiver()
        val before = System.currentTimeMillis()
        val intent = Intent().apply {
            putExtra("account_id", "test-account-123")
            putExtra("currency", "USD")
        }

        receiver.onReceive(context, intent)

        assertTrue(prefs.getSnoozeUntil("test-account-123") >= before + 60 * 60_000L)
        assertEquals(0L, prefs.getSnoozeUntil("other-account"))
    }

    @Test
    fun `onReceive without accountId is no-op`() {
        val receiver = SnoozeReceiver()
        val intent = Intent() // no account_id
        receiver.onReceive(context, intent)
    }
}
