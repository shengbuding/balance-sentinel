package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.app.NotificationManager
import com.balancesentinel.app.data.repository.NotificationHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.robolectric.Shadows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SnoozeReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `onReceive with accountId does not throw`() {
        val receiver = SnoozeReceiver()
        val intent = Intent().apply {
            putExtra("account_id", "test-account-123")
        }
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive without accountId is no-op`() {
        val receiver = SnoozeReceiver()
        val intent = Intent() // no account_id
        receiver.onReceive(context, intent)
    }
    @Test
    fun `snoozing one currency does not cancel another currency notification`() {
        val helper = NotificationHelper(context)
        helper.sendLowBalanceAlert("test-account-123", 10f, 20f, "CNY", "Main")
        helper.sendLowBalanceAlert("test-account-123", 10f, 20f, "USD", "Main")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(manager)
        val cnyId = helper.alertNotificationId("test-account-123", "CNY")
        val usdId = helper.alertNotificationId("test-account-123", "USD")
        assertNotNull(shadow.getNotification(cnyId))
        assertNotNull(shadow.getNotification(usdId))

        SnoozeReceiver().onReceive(context, Intent().apply {
            putExtra("account_id", "test-account-123")
            putExtra("deep_link_currency", "CNY")
        })

        assertNull(shadow.getNotification(cnyId))
        assertNotNull(shadow.getNotification(usdId))
        manager.cancelAll()
    }

}
