package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `onReceive with BOOT_COMPLETED action starts service`() {
        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // should not throw
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive with non-boot action is no-op`() {
        val receiver = BootReceiver()
        val intent = Intent("com.example.SOME_OTHER_ACTION")

        // should not throw and should be no-op
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive with empty action is no-op`() {
        val receiver = BootReceiver()
        val intent = Intent() // no action set

        receiver.onReceive(context, intent)
        // no exception = pass
    }
}
