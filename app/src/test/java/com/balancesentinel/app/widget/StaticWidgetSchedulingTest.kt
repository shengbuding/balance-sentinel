package com.balancesentinel.app.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.repository.HEARTBEAT_GRACE_MS
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.SCHEDULE_GRACE_MS
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaticWidgetSchedulingTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        schedulerPrefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        schedulerPrefs().edit().clear().commit()
    }

    @Test
    fun `manual and watchdog intents use distinct production actions`() {
        val manual = WidgetRefreshIntents.manual(context, StaticWidgetProvider_2x1::class.java)
        val watchdog = WidgetRefreshIntents.watchdog(context)

        assertEquals(StaticWidgetProvider.ACTION_REFRESH_NOW, manual.action)
        assertEquals(StaticWidgetProvider.ACTION_WATCHDOG, watchdog.action)
        assertEquals(StaticWidgetProvider_2x1::class.java.name, manual.component?.className)
        assertEquals(context.packageName, watchdog.`package`)
    }

    @Test
    fun `manifest resolves watchdog action to every widget receiver`() {
        val intent = Intent(StaticWidgetProvider.ACTION_WATCHDOG).setPackage(context.packageName)

        val receiverNames = context.packageManager.queryBroadcastReceivers(intent, 0)
            .map { it.activityInfo.name }
            .toSet()

        assertEquals(
            setOf(
                StaticWidgetProvider_2x1::class.java.name,
                StaticWidgetProvider_2x2::class.java.name,
                StaticWidgetProvider_3x1::class.java.name,
                StaticWidgetProvider_4x2::class.java.name,
                StaticWidgetProvider_5x1::class.java.name
            ),
            receiverNames
        )
    }

    @Test
    fun `ordinary watchdog preserves a valid expected refresh`() {
        val now = System.currentTimeMillis()
        val expectedNext = now + 1_800_000L
        RefreshScheduler.recordSchedule(context, 1800, expectedNext, "alarm")
        schedulerPrefs().edit()
            .putLong("last_heartbeat", now - HEARTBEAT_GRACE_MS - 1L)
            .apply()

        val decision = WidgetRefreshActionHandler().decide(
            context,
            StaticWidgetProvider.ACTION_WATCHDOG,
            now
        )

        assertEquals(WidgetRefreshDecision.Ignored, decision)
        assertEquals(expectedNext, RefreshScheduler.getState(context).expectedNextAt)
    }

    @Test
    fun `manual action always selects immediate non watchdog refresh`() {
        val decision = WidgetRefreshActionHandler().decide(
            context,
            StaticWidgetProvider.ACTION_REFRESH_NOW,
            System.currentTimeMillis()
        )

        assertEquals(WidgetRefreshDecision.Refresh(watchdog = false), decision)
    }

    @Test
    fun `overdue stale watchdog selects takeover refresh`() {
        val now = System.currentTimeMillis()
        RefreshScheduler.recordSchedule(
            context,
            30,
            now - SCHEDULE_GRACE_MS - 1L,
            "alarm"
        )
        schedulerPrefs().edit()
            .putLong("last_heartbeat", now - HEARTBEAT_GRACE_MS - 1L)
            .putLong("service_start_requested_at", 0L)
            .putLong("refresh_deadline_at", 0L)
            .apply()

        val decision = WidgetRefreshActionHandler().decide(
            context,
            StaticWidgetProvider.ACTION_WATCHDOG,
            now
        )

        assertEquals(WidgetRefreshDecision.Refresh(watchdog = true), decision)
    }

    @Test
    fun `manual execution refreshes with WIDGET and does not start service`() = runTest {
        val events = mutableListOf<String>()
        val starter = RecordingStarter(events)
        val execution = WidgetRefreshExecution(RecordingGateway(events), starter)

        execution.execute(context, WidgetRefreshDecision.Refresh(watchdog = false))

        assertEquals(listOf("refresh:WIDGET"), events)
        assertEquals(0, starter.calls)
    }

    @Test
    fun `watchdog execution refreshes with WATCHDOG before requesting service restart`() = runTest {
        val events = mutableListOf<String>()
        val starter = RecordingStarter(events)
        val execution = WidgetRefreshExecution(RecordingGateway(events), starter)

        execution.execute(context, WidgetRefreshDecision.Refresh(watchdog = true))

        assertEquals(listOf("refresh:WATCHDOG", "start"), events)
        assertEquals(1, starter.calls)
    }

    private fun schedulerPrefs() =
        context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)

    private class RecordingGateway(
        private val events: MutableList<String>
    ) : RefreshGateway {
        override suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult> {
            events += "refresh:${trigger.name}"
            return emptyList()
        }

        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
            error("not used")

        override fun invalidate(accountId: String) = Unit
    }

    private class RecordingStarter(
        private val events: MutableList<String>
    ) : ServiceStarter {
        var calls = 0

        override fun start(context: Context): ServiceStartResult {
            calls += 1
            events += "start"
            return ServiceStartResult.Started
        }
    }
}
