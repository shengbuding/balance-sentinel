package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.MidnightScheduler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MidnightReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `onReceive with midnight action runs without throwing`() {
        val receiver = MidnightReceiver()
        val intent = Intent(MidnightScheduler.ACTION_MIDNIGHT)

        receiver.onReceive(context, intent)
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
