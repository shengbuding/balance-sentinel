package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.WidgetPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SnoozeReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
