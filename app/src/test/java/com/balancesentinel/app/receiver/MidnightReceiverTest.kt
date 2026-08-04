package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.MidnightScheduler
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MidnightReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `midnight broadcast finishes its asynchronous pending result`() {
        val receiver = MidnightReceiver()
        val intent = Intent(MidnightScheduler.ACTION_MIDNIGHT)

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(MidnightScheduler.ACTION_MIDNIGHT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()

            val receiverShadow = shadowOf(receiver)
            assertTrue("midnight work must use goAsync", receiverShadow.wentAsync())
            val pendingResult = receiverShadow.originalPendingResult
            assertNotNull("framework dispatch must provide a pending result", pendingResult)
            shadowOf(pendingResult).future.get(5, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun `onReceive with non-midnight action is no-op`() {
        val receiver = MidnightReceiver()
        val intent = Intent("com.example.OTHER_ACTION")

        receiver.onReceive(context, intent)
        // no exception = pass
    }

    @Test
    fun `onReceive with empty action is no-op`() {
        val receiver = MidnightReceiver()
        val intent = Intent()

        receiver.onReceive(context, intent)
        // no exception = pass
    }
}
