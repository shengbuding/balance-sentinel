package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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
}
