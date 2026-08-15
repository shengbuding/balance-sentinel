package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    private fun clearPrefs() {
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `recordSchedule stores all fields`() {
        val now = System.currentTimeMillis()
        val expectedTime = now + 30_000
        RefreshScheduler.recordSchedule(context, 30, expectedTime, "exact")

        val state = RefreshScheduler.getState(context)
        assertTrue(state.lastScheduledAt > 0)
        assertEquals(expectedTime, state.expectedNextAt)
        assertEquals(30, state.intervalSeconds)
        assertEquals("exact", state.alarmMethod)
        assertEquals(1, state.totalAlarmsSet)
    }

    @Test
    fun `recordSchedule increments totalAlarmsSet`() {
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 30_000, "exact")
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 60_000, "exact")

        val state = RefreshScheduler.getState(context)
        assertEquals(2, state.totalAlarmsSet)
    }

    @Test
    fun `markFired updates fired timestamp and count`() {
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 30_000, "exact")
        RefreshScheduler.markFired(context)

        val state = RefreshScheduler.getState(context)
        assertTrue(state.alarmFiredAt > 0)
        assertEquals(1, state.totalAlarmsFired)
        assertEquals(0, state.expectedNextAt) // cleared after fire
    }

    @Test
    fun `markCancelled increments counter`() {
        RefreshScheduler.markCancelled(context)
        RefreshScheduler.markCancelled(context)

        val state = RefreshScheduler.getState(context)
        assertEquals(2, state.totalCancelled)
    }

    @Test
    fun `markDropped increments counter`() {
        RefreshScheduler.markDropped(context)

        val state = RefreshScheduler.getState(context)
        assertEquals(1, state.totalDropped)
    }

    @Test
    fun `heartbeat updates last heartbeat time`() {
        RefreshScheduler.heartbeat(context)

        val summary = RefreshScheduler.getStatusSummary(context)
        assertTrue(summary.lastHeartbeat > 0)
    }

    @Test
    fun `isServiceDead returns false when no schedule exists`() {
        assertFalse(RefreshScheduler.isServiceDead(context))
    }

    @Test
    fun `isServiceDead returns false right after heartbeat`() {
        RefreshScheduler.heartbeat(context)
        assertFalse(RefreshScheduler.isServiceDead(context))
    }

    @Test
    fun `isServiceDead ignores heartbeat timeout without an overdue schedule`() {
        RefreshScheduler.heartbeat(context)
        assertFalse(RefreshScheduler.isServiceDead(context, timeoutMs = 0L))
    }

    @Test
    fun `recordRestart increments counter`() {
        RefreshScheduler.recordRestart(context)
        RefreshScheduler.recordRestart(context)
        RefreshScheduler.recordRestart(context)

        assertEquals(3, RefreshScheduler.getRestartCount(context))
    }

    @Test
    fun `getRestartCount returns zero initially`() {
        assertEquals(0, RefreshScheduler.getRestartCount(context))
    }

    @Test
    fun `getStatusSummary includes all fields`() {
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 30_000, "exact")
        RefreshScheduler.heartbeat(context)

        val summary = RefreshScheduler.getStatusSummary(context)
        assertTrue(summary.lastHeartbeat > 0)
        assertTrue(summary.expectedNextRefresh > 0)
        assertEquals("exact", summary.alarmMethod)
        assertEquals(1, summary.totalSet)
    }

    @Test
    fun `checkMissedRefresh returns empty when no schedule recorded`() {
        val missed = RefreshScheduler.checkMissedRefresh(context)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `getState returns zeros for fresh prefs`() {
        val state = RefreshScheduler.getState(context)
        assertEquals(0, state.lastScheduledAt)
        assertEquals(0, state.expectedNextAt)
        assertEquals(0, state.totalAlarmsSet)
        assertEquals(0, state.totalAlarmsFired)
    }

    @Test
    fun `legacy foreground intent requires legacy scheduler evidence`() {
        assertFalse(RefreshScheduler.hasLegacyForegroundIntent(context))

        RefreshScheduler.recordSchedule(
            context,
            intervalSeconds = 30,
            expectedTriggerTime = System.currentTimeMillis() + 30_000L,
            method = "foreground_service"
        )

        assertTrue(RefreshScheduler.hasLegacyForegroundIntent(context))
    }

    @Test
    fun `work manager plans do not opt into foreground monitoring`() {
        RefreshScheduler.recordSchedule(
            context,
            intervalSeconds = 900,
            expectedTriggerTime = System.currentTimeMillis() + 900_000L,
            method = "work_manager_periodic"
        )

        assertFalse(RefreshScheduler.hasLegacyForegroundIntent(context))
    }

    @Test
    fun `legacy heartbeat restores foreground intent`() {
        RefreshScheduler.heartbeat(context)

        assertTrue(RefreshScheduler.hasLegacyForegroundIntent(context))
    }

    // ── resetAlarmCounters ──

    @Test
    fun `resetAlarmCounters zeros all counters and timestamps`() {
        // Set up non-zero state
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 30_000, "exact")
        RefreshScheduler.markFired(context)
        RefreshScheduler.markCancelled(context)
        RefreshScheduler.markDropped(context)
        RefreshScheduler.markDropped(context)

        val before = RefreshScheduler.getState(context)
        assertEquals(1, before.totalAlarmsSet)
        assertEquals(1, before.totalAlarmsFired)
        assertEquals(1, before.totalCancelled)
        assertEquals(2, before.totalDropped)

        RefreshScheduler.resetAlarmCounters(context)

        val after = RefreshScheduler.getState(context)
        assertEquals(0, after.totalAlarmsSet)
        assertEquals(0, after.totalAlarmsFired)
        assertEquals(0, after.totalCancelled)
        assertEquals(0, after.totalDropped)
        assertEquals(0, after.expectedNextAt)
        assertEquals(0, after.alarmFiredAt)
    }

    @Test
    fun `resetAlarmCounters is idempotent`() {
        // Should not throw on fresh state
        RefreshScheduler.resetAlarmCounters(context)
        val state = RefreshScheduler.getState(context)
        assertEquals(0, state.totalAlarmsSet)

        RefreshScheduler.resetAlarmCounters(context)
        assertEquals(0, state.totalAlarmsSet)
    }

    // ═══════════════════════════════════════════════════════════
    // markStartRequested / isServiceStarting
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `markStartRequested sets start timestamp`() {
        RefreshScheduler.markStartRequested(context)
        // Verifying via isServiceStarting — should return true within 5s
        assertTrue(RefreshScheduler.isServiceStarting(context))
    }

    @Test
    fun `isServiceStarting returns false when no start requested`() {
        assertFalse(RefreshScheduler.isServiceStarting(context))
    }

    @Test
    fun `isServiceStarting returns false after heartbeat`() {
        RefreshScheduler.markStartRequested(context)
        RefreshScheduler.heartbeat(context)
        assertFalse(RefreshScheduler.isServiceStarting(context))
    }

    // ═══════════════════════════════════════════════════════════
    // isServiceDead — timeout edge cases
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `isServiceDead with zero timeout still requires an overdue schedule`() {
        RefreshScheduler.heartbeat(context)
        assertFalse(RefreshScheduler.isServiceDead(context, timeoutMs = 0L))
    }

    @Test
    fun `isServiceDead returns false during start grace period`() {
        RefreshScheduler.markStartRequested(context)
        // Service is starting — should NOT be considered dead
        assertFalse(RefreshScheduler.isServiceDead(context))
    }

    // ═══════════════════════════════════════════════════════════
    // checkMissedRefresh — expired schedule path
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `checkMissedRefresh detects expired schedule`() {
        val pastTime = System.currentTimeMillis() - 120_000 // 2 minutes ago
        val state = RefreshScheduler.getState(context)
        // Write a schedule that has already expired
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("expected_next_at", pastTime)
            .putInt("scheduled_interval", 30)
            .putString("alarm_method", "inexact")
            .apply()

        val missed = RefreshScheduler.checkMissedRefresh(context)
        assertEquals(1, missed.size)
        assertEquals("MISSED", missed[0].type.name)
        assertTrue(missed[0].message.contains("延迟"))
        // Should have incremented dropped counter
        val newState = RefreshScheduler.getState(context)
        assertEquals(1, newState.totalDropped)
    }

    @Test
    fun `checkMissedRefresh returns empty within grace period`() {
        val justScheduled = System.currentTimeMillis() + 30_000 // still in future
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("expected_next_at", justScheduled)
            .putInt("scheduled_interval", 30)
            .apply()

        val missed = RefreshScheduler.checkMissedRefresh(context)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `checkMissedRefresh returns empty when expectedNextAt is zero`() {
        val prefs = context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)
        prefs.edit().putLong("expected_next_at", 0).putInt("scheduled_interval", 0).apply()
        val missed = RefreshScheduler.checkMissedRefresh(context)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `stale heartbeat does not restart before next refresh is overdue`() {
        val now = 1_000_000L
        val state = ServiceHealthState(
            expectedNextAt = now + 20 * 60_000L,
            lastHeartbeat = now - 5 * 60_000L,
            startRequestedAt = 0L,
            refreshDeadlineAt = 0L
        )

        assertFalse(ServiceHealthEvaluator.shouldRestart(state, now))
    }

    @Test
    fun `overdue schedule and stale heartbeat restart outside grace windows`() {
        val now = 1_000_000L
        val state = ServiceHealthState(
            expectedNextAt = now - SCHEDULE_GRACE_MS - 1L,
            lastHeartbeat = now - HEARTBEAT_GRACE_MS - 1L,
            startRequestedAt = now - STARTUP_GRACE_MS - 1L,
            refreshDeadlineAt = now - 1L
        )

        assertTrue(ServiceHealthEvaluator.shouldRestart(state, now))
    }

    @Test
    fun `missing schedule never requests restart from heartbeat state alone`() {
        val now = 1_000_000L
        val state = ServiceHealthState(
            expectedNextAt = 0L,
            lastHeartbeat = 0L,
            startRequestedAt = 0L,
            refreshDeadlineAt = 0L
        )

        assertFalse(ServiceHealthEvaluator.shouldRestart(state, now))
    }

    @Test
    fun `startup grace suppresses restart for an overdue stale service`() {
        val now = 1_000_000L
        val state = ServiceHealthState(
            expectedNextAt = now - SCHEDULE_GRACE_MS - 1L,
            lastHeartbeat = now - HEARTBEAT_GRACE_MS - 1L,
            startRequestedAt = now - STARTUP_GRACE_MS,
            refreshDeadlineAt = 0L
        )

        assertFalse(ServiceHealthEvaluator.shouldRestart(state, now))
    }

    @Test
    fun `active refresh deadline suppresses restart for an overdue stale service`() {
        val now = 1_000_000L
        val state = ServiceHealthState(
            expectedNextAt = now - SCHEDULE_GRACE_MS - 1L,
            lastHeartbeat = now - HEARTBEAT_GRACE_MS - 1L,
            startRequestedAt = 0L,
            refreshDeadlineAt = now
        )

        assertFalse(ServiceHealthEvaluator.shouldRestart(state, now))
    }

    @Test
    fun `markRefreshStarted persists exact account scaled deadline`() {
        val now = 2_000_000L

        val deadline = RefreshScheduler.markRefreshStarted(context, accountCount = 3, now = now)

        assertEquals(2_090_000L, deadline)
        assertEquals(2_090_000L, RefreshScheduler.getServiceHealthState(context).refreshDeadlineAt)
    }

    @Test
    fun `heartbeat preserves schedule and active refresh deadline`() {
        val expectedNext = 3_000_000L
        RefreshScheduler.recordSchedule(context, 1800, expectedNext, "alarm")
        RefreshScheduler.markRefreshStarted(context, accountCount = 2, now = 2_000_000L)

        RefreshScheduler.heartbeat(context)

        val schedule = RefreshScheduler.getState(context)
        val health = RefreshScheduler.getServiceHealthState(context)
        assertEquals(expectedNext, schedule.expectedNextAt)
        assertEquals(2_070_000L, health.refreshDeadlineAt)
        assertTrue(health.lastHeartbeat > 0L)
    }

    @Test
    fun `clearing refresh deadline preserves schedule and heartbeat`() {
        val expectedNext = 3_000_000L
        RefreshScheduler.recordSchedule(context, 1800, expectedNext, "alarm")
        RefreshScheduler.heartbeat(context)
        val heartbeat = RefreshScheduler.getServiceHealthState(context).lastHeartbeat
        RefreshScheduler.markRefreshStarted(context, accountCount = 1, now = 2_000_000L)

        RefreshScheduler.clearRefreshDeadline(context)

        val health = RefreshScheduler.getServiceHealthState(context)
        assertEquals(expectedNext, health.expectedNextAt)
        assertEquals(heartbeat, health.lastHeartbeat)
        assertEquals(0L, health.refreshDeadlineAt)
    }
}
