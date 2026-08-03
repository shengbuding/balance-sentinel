package com.balancesentinel.app.receiver

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var starter: RecordingStarter

    @Before
    fun setUp() {
        starter = RecordingStarter()
        KeepAliveReceiver.cancel(context)
        val shadowApplication = Shadows.shadowOf(context as Application)
        while (shadowApplication.nextStartedService != null) {
            // Drain starts left by a prior test in the same Robolectric sandbox.
        }
    }

    @Test
    fun `onReceive with BOOT_COMPLETED action starts service`() {
        val receiver = BootReceiver(starter)
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)

        assertEquals(1, starter.calls)
        assertNull(Shadows.shadowOf(context as Application).nextStartedService)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(1, Shadows.shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `onReceive with non-boot action is no-op`() {
        val receiver = BootReceiver(starter)
        val intent = Intent("com.example.SOME_OTHER_ACTION")

        // should not throw and should be no-op
        receiver.onReceive(context, intent)

        assertEquals(0, starter.calls)
    }

    @Test
    fun `onReceive with empty action is no-op`() {
        val receiver = BootReceiver(starter)
        val intent = Intent() // no action set

        receiver.onReceive(context, intent)
        assertEquals(0, starter.calls)
    }

    private class RecordingStarter : ServiceStarter {
        var calls = 0

        override fun start(context: Context): ServiceStartResult {
            calls += 1
            return ServiceStartResult.Started
        }
    }
}
