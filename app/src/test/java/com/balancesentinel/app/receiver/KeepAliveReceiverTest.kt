package com.balancesentinel.app.receiver

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.repository.HEARTBEAT_GRACE_MS
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.SCHEDULE_GRACE_MS
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KeepAliveReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KeepAliveReceiver.cancel(context)
        // Ensure clean refresh scheduler state
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val shadowApplication = Shadows.shadowOf(context as Application)
        while (shadowApplication.nextStartedService != null) {
            // Drain starts left by a prior test in the same Robolectric sandbox.
        }
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

    // ═══════════════════════════════════════════════════════════
    // onReceive — service dead detection
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `onReceive with no schedule does not restart for missing heartbeat`() {
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_heartbeat", 0)
            .putLong("service_start_requested_at", 0)
            .apply()
        val starter = RecordingStarter()

        val receiver = KeepAliveReceiver(starter)
        val intent = Intent(KeepAliveReceiver.ACTION_KEEPALIVE)
        receiver.onReceive(context, intent)

        assertEquals(0, starter.calls)
    }

    @Test
    fun `onReceive with no schedule does not restart for old heartbeat`() {
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        val oldHeartbeat = System.currentTimeMillis() - HEARTBEAT_GRACE_MS - 1L
        prefs.edit()
            .putLong("last_heartbeat", oldHeartbeat)
            .putLong("service_start_requested_at", 0)
            .apply()
        val starter = RecordingStarter()

        val receiver = KeepAliveReceiver(starter)
        val intent = Intent(KeepAliveReceiver.ACTION_KEEPALIVE)
        receiver.onReceive(context, intent)

        assertEquals(0, starter.calls)
    }

    @Test
    fun `onReceive during start grace period does not restart overdue schedule`() {
        val now = System.currentTimeMillis()
        RefreshScheduler.recordSchedule(context, 30, now - SCHEDULE_GRACE_MS - 1L, "alarm")
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_heartbeat", now - HEARTBEAT_GRACE_MS - 1L)
            .putLong("service_start_requested_at", now)
            .apply()
        val starter = RecordingStarter()

        val receiver = KeepAliveReceiver(starter)
        val intent = Intent(KeepAliveReceiver.ACTION_KEEPALIVE)
        receiver.onReceive(context, intent)

        assertEquals(0, starter.calls)
    }

    @Test
    fun `stale heartbeat with future refresh neither starts helper nor OS service`() {
        val now = System.currentTimeMillis()
        RefreshScheduler.recordSchedule(context, 1800, now + 1_800_000L, "alarm")
        context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE).edit()
            .putLong("last_heartbeat", now - HEARTBEAT_GRACE_MS - 1L)
            .putLong("service_start_requested_at", 0L)
            .apply()
        val starter = RecordingStarter()

        KeepAliveReceiver(starter).onReceive(context, Intent(KeepAliveReceiver.ACTION_KEEPALIVE))

        assertEquals(0, starter.calls)
        assertNull(Shadows.shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `overdue schedule and stale heartbeat delegate one restart to starter`() {
        val now = System.currentTimeMillis()
        RefreshScheduler.recordSchedule(
            context,
            30,
            now - SCHEDULE_GRACE_MS - 1L,
            "alarm"
        )
        context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE).edit()
            .putLong("last_heartbeat", now - HEARTBEAT_GRACE_MS - 1L)
            .putLong("service_start_requested_at", 0L)
            .apply()
        val starter = RecordingStarter()

        KeepAliveReceiver(starter).onReceive(context, Intent(KeepAliveReceiver.ACTION_KEEPALIVE))

        assertEquals(1, starter.calls)
        assertNull(Shadows.shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `active refresh deadline suppresses keepalive restart`() {
        val now = System.currentTimeMillis()
        RefreshScheduler.recordSchedule(
            context,
            30,
            now - SCHEDULE_GRACE_MS - 1L,
            "alarm"
        )
        context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE).edit()
            .putLong("last_heartbeat", now - HEARTBEAT_GRACE_MS - 1L)
            .putLong("service_start_requested_at", 0L)
            .putLong("refresh_deadline_at", now + 20_000L)
            .apply()
        val starter = RecordingStarter()

        KeepAliveReceiver(starter).onReceive(context, Intent(KeepAliveReceiver.ACTION_KEEPALIVE))

        assertEquals(0, starter.calls)
        assertNull(Shadows.shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `zz watchdog diagnostics do not leak beyond their owning test`() = runBlocking {
        val logCount = runCatching {
            WalletDatabaseProvider.get(context).eventLogDao().countLogs()
        }.getOrNull()

        assertNotNull("database fixture should be usable", logCount)
        assertEquals(0L, logCount)
    }

    private class RecordingStarter : ServiceStarter {
        var calls = 0

        override fun start(context: Context): ServiceStartResult {
            calls += 1
            return ServiceStartResult.Started
        }
    }
}
