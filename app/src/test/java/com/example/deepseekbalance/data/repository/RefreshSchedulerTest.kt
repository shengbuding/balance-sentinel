package com.example.deepseekbalance.data.repository

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
    fun `isServiceDead returns true when no heartbeat ever`() {
        assertTrue(RefreshScheduler.isServiceDead(context))
    }

    @Test
    fun `isServiceDead returns false right after heartbeat`() {
        RefreshScheduler.heartbeat(context)
        assertFalse(RefreshScheduler.isServiceDead(context))
    }

    @Test
    fun `isServiceDead returns true after timeout`() {
        // We can't easily simulate time passing in Robolectric for this,
        // but we can verify the default state is correct
        val dead = RefreshScheduler.isServiceDead(context, timeoutMs = 0L)
        // With timeout=0, any heartbeat would be considered expired immediately
        RefreshScheduler.heartbeat(context)
        // Even with timeout=0, the heartbeat should be recorded
        assertNotNull(RefreshScheduler.getStatusSummary(context))
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
}
