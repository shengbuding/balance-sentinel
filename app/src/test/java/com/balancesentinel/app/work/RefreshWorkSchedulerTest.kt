package com.balancesentinel.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.widget.StaticWidgetProvider
import com.balancesentinel.app.widget.WidgetRefreshActionHandler
import com.balancesentinel.app.widget.WidgetRefreshDecision
import com.balancesentinel.app.widget.WidgetRefreshIntents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class RefreshWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var runtime: RecordingWorkRuntime
    private lateinit var scheduler: RefreshWorkScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runtime = RecordingWorkRuntime()
        scheduler = RefreshWorkScheduler(runtime)
    }

    @After
    fun tearDown() {
        runtime.clear()
    }

    @Test
    fun `duplicate bootstrap keeps one stable periodic work with connected constraint`() {
        scheduler.reconcile(context, backgroundIntervalSeconds = 1_800)
        scheduler.reconcile(context, backgroundIntervalSeconds = 3_600)

        val specs = runtime.periodic.values.toList()
        assertEquals(1, specs.size)
        assertEquals(RefreshWorkScheduler.PERIODIC_WORK_NAME, specs.single().uniqueName)
        assertEquals(3_600L, specs.single().intervalSeconds)
        assertTrue(specs.single().requiresNetwork)
    }

    @Test
    fun `reconcile after process reconstruction does not create parallel work`() {
        repeat(3) {
            RefreshWorkScheduler(runtime).reconcile(context, backgroundIntervalSeconds = 1_800)
        }

        assertEquals(1, runtime.periodic.size)
    }

    @Test
    fun `background interval below fifteen minutes is clamped while foreground session owns short cadence`() {
        scheduler.reconcile(
            context,
            backgroundIntervalSeconds = 300,
            foregroundSessionActive = false
        )

        assertEquals(900L, runtime.periodic.values.single().intervalSeconds)
    }

    @Test
    fun `first reconcile cancels legacy widget alarm pending intent`() {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val legacy = PendingIntent.getBroadcast(
            context,
            StaticWidgetProvider.LEGACY_WIDGET_ALARM_REQUEST_CODE,
            WidgetRefreshIntents.watchdog(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L, legacy)
        assertEquals(1, Shadows.shadowOf(alarm).scheduledAlarms.size)

        scheduler.reconcile(context, backgroundIntervalSeconds = 1_800)

        assertEquals(0, Shadows.shadowOf(alarm).scheduledAlarms.size)
    }

    @Test
    fun `widget periodic refresh uses the shared periodic work and manual click remains immediate`() {
        scheduler.reconcile(context, backgroundIntervalSeconds = 1_800, widgetEnabled = true)

        assertEquals(setOf(RefreshWorkScheduler.PERIODIC_WORK_NAME), runtime.periodic.keys)
        val decision = WidgetRefreshActionHandler().decide(
            context,
            StaticWidgetProvider.ACTION_REFRESH_NOW,
            System.currentTimeMillis()
        )
        assertEquals(WidgetRefreshDecision.Refresh(watchdog = false), decision)
        assertTrue(WidgetRefreshIntents.manual(context).component != null)
        assertFalse(runtime.periodic.keys.any { it.contains("widget", ignoreCase = true) })
    }
}

private class RecordingWorkRuntime : WorkRuntime {
    val periodic = linkedMapOf<String, PeriodicWorkSpec>()
    val oneShot = linkedMapOf<String, OneShotWorkSpec>()

    override fun enqueuePeriodic(context: Context, spec: PeriodicWorkSpec) {
        periodic[spec.uniqueName] = spec
    }

    override fun enqueueOneShot(context: Context, spec: OneShotWorkSpec) {
        oneShot[spec.uniqueName] = spec
    }

    override fun cancelUnique(context: Context, uniqueName: String) {
        periodic.remove(uniqueName)
        oneShot.remove(uniqueName)
    }

    fun clear() {
        periodic.clear()
        oneShot.clear()
    }
}