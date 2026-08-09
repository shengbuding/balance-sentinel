package com.balancesentinel.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.testing.WorkManagerTestInitHelper
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
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: RefreshWorkScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().get()
        scheduler = RefreshWorkScheduler()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().get()
    }

    @Test
    fun `duplicate bootstrap keeps one stable periodic work with connected constraint`() {
        scheduler.reconcile(context, backgroundIntervalSeconds = 1_800)
        scheduler.reconcile(context, backgroundIntervalSeconds = 3_600)

        val infos = workManager
            .getWorkInfosForUniqueWork(RefreshWorkScheduler.PERIODIC_WORK_NAME)
            .get()

        assertEquals(1, infos.size)
        assertEquals(3_600L, infos.single().inputData.getLong(RefreshWorkScheduler.KEY_INTERVAL_SECONDS, -1L))
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    @Test
    fun `reconcile after process reconstruction does not create parallel work`() {
        repeat(3) {
            RefreshWorkScheduler().reconcile(context, backgroundIntervalSeconds = 1_800)
        }

        val infos = workManager
            .getWorkInfosForUniqueWork(RefreshWorkScheduler.PERIODIC_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `background interval below fifteen minutes is clamped while foreground session owns short cadence`() {
        scheduler.reconcile(
            context,
            backgroundIntervalSeconds = 300,
            foregroundSessionActive = false
        )

        val info = workManager
            .getWorkInfosForUniqueWork(RefreshWorkScheduler.PERIODIC_WORK_NAME)
            .single()
        assertEquals(900L, info.inputData.getLong(RefreshWorkScheduler.KEY_INTERVAL_SECONDS, -1L))
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

        val uniqueNames = workManager
            .getWorkInfosForUniqueWork(RefreshWorkScheduler.PERIODIC_WORK_NAME)
            .get()
            .map { it.tags }
            .flatten()
        assertFalse(uniqueNames.any { it.contains("widget", ignoreCase = true) })

        val decision = WidgetRefreshActionHandler().decide(
            context,
            StaticWidgetProvider.ACTION_REFRESH_NOW,
            System.currentTimeMillis()
        )
        assertEquals(WidgetRefreshDecision.Refresh(watchdog = false), decision)
        assertTrue(WidgetRefreshIntents.manual(context).component != null)
    }
}