package com.balancesentinel.app.receiver

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class KeepAliveReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KeepAliveReceiver.cancel(context)
    }

    @Test
    fun `schedule sets an alarm`() {
        KeepAliveReceiver.schedule(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)
    }

    @Test
    fun `schedule replaces previous alarm`() {
        KeepAliveReceiver.schedule(context)
        KeepAliveReceiver.schedule(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)
    }

    @Test
    fun `cancel removes alarm`() {
        KeepAliveReceiver.schedule(context)
        KeepAliveReceiver.cancel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(0, shadow.scheduledAlarms.size)
    }

    @Test
    fun `onReceive with keepalive action does not throw`() {
        val receiver = KeepAliveReceiver()
        val intent = Intent(KeepAliveReceiver.ACTION_KEEPALIVE)
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive with non-keepalive action is no-op`() {
        val receiver = KeepAliveReceiver()
        val intent = Intent("com.example.OTHER")
        receiver.onReceive(context, intent)
    }
}
