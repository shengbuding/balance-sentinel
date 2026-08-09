package com.balancesentinel.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshBatchState
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import com.balancesentinel.app.data.repository.HEARTBEAT_GRACE_MS
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.SCHEDULE_GRACE_MS
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        WidgetRefreshStatusStore.clearForTests(context)
    }

    @After
    fun tearDown() {
        schedulerPrefs().edit().clear().commit()
        WidgetRefreshStatusStore.clearForTests(context)
    }

    @Test
    fun `manual and watchdog intents target one non exported internal receiver`() {
        val manual = WidgetRefreshIntents.manual(context)
        val watchdog = WidgetRefreshIntents.watchdog(context)
        val expectedComponent = ComponentName(context.packageName, INTERNAL_RECEIVER_CLASS)

        assertEquals(StaticWidgetProvider.ACTION_REFRESH_NOW, manual.action)
        assertEquals(StaticWidgetProvider.ACTION_WATCHDOG, watchdog.action)
        assertEquals(expectedComponent, manual.component)
        assertEquals(expectedComponent, watchdog.component)
        assertFalse(context.packageManager.getReceiverInfo(expectedComponent, 0).exported)
    }

    @Test
    fun `implicit custom refresh actions expose no manifest receivers`() {
        listOf(
            StaticWidgetProvider.ACTION_REFRESH_NOW,
            StaticWidgetProvider.ACTION_WATCHDOG
        ).forEach { action ->
            val intent = Intent(action).setPackage(context.packageName)

            assertTrue(context.packageManager.queryBroadcastReceivers(intent, 0).isEmpty())
        }
    }

    @Test
    fun `all five exported providers still resolve system widget updates`() {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName)
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
        val receiverNames = receivers
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
        assertTrue(receivers.all { it.activityInfo.exported })
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
    fun `widget execution delivers the real batch result to its consumer`() = runTest {
        val events = mutableListOf<String>()
        val starter = RecordingStarter(events)
        var observed: RefreshBatchResult? = null
        val execution = WidgetRefreshExecution(
            RecordingGateway(events),
            starter,
            WidgetRefreshResultConsumer { result -> observed = result }
        )

        execution.execute(context, WidgetRefreshDecision.Refresh(watchdog = false))

        assertEquals("test-run", observed?.runId)
        assertEquals(RefreshBatchState.FAILED, observed?.aggregate?.state)
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

    @Test
    fun `watchdog execution restarts exactly once and rethrows refresh failure`() = runTest {
        val events = mutableListOf<String>()
        val expectedFailure = IllegalStateException("watchdog refresh failed")
        val starter = RecordingStarter(events)
        val execution = WidgetRefreshExecution(
            FailingGateway(events, expectedFailure),
            starter
        )

        val observedFailure = try {
            execution.execute(context, WidgetRefreshDecision.Refresh(watchdog = true))
            null
        } catch (failure: IllegalStateException) {
            failure
        }

        assertSame(expectedFailure, observedFailure)
        assertEquals(listOf("refresh:WATCHDOG", "start"), events)
        assertEquals(1, starter.calls)
        assertEquals(1, RefreshScheduler.getRestartCount(context))
    }

    @Test
    fun `watchdog execution restarts exactly once and preserves cancellation`() = runTest {
        val events = mutableListOf<String>()
        val starter = RecordingStarter(events)
        val execution = WidgetRefreshExecution(CancellingGateway(events), starter)
        val cancellation = CancellationException("watchdog refresh cancelled")
        val executionResult = async {
            execution.execute(context, WidgetRefreshDecision.Refresh(watchdog = true))
        }

        testScheduler.advanceUntilIdle()
        executionResult.cancel(cancellation)
        val observedCancellation = try {
            executionResult.await()
            null
        } catch (failure: CancellationException) {
            failure
        }

        assertEquals(cancellation.message, observedCancellation?.message)
        assertTrue(executionResult.isCancelled)
        assertEquals(listOf("refresh:WATCHDOG", "start"), events)
        assertEquals(1, starter.calls)
        assertEquals(1, RefreshScheduler.getRestartCount(context))
    }

    @Test
    fun `manual execution rethrows refresh failure without starting service`() = runTest {
        val events = mutableListOf<String>()
        val expectedFailure = IllegalStateException("manual refresh failed")
        val starter = RecordingStarter(events)
        val execution = WidgetRefreshExecution(
            FailingGateway(events, expectedFailure),
            starter
        )

        val observedFailure = try {
            execution.execute(context, WidgetRefreshDecision.Refresh(watchdog = false))
            null
        } catch (failure: IllegalStateException) {
            failure
        }

        assertSame(expectedFailure, observedFailure)
        assertEquals(listOf("refresh:WIDGET"), events)
        assertEquals(0, starter.calls)
        assertEquals(0, RefreshScheduler.getRestartCount(context))
    }

    private companion object {
        const val INTERNAL_RECEIVER_CLASS =
            "com.balancesentinel.app.widget.WidgetRefreshReceiver"

        fun batch(results: List<AccountRefreshResult>) = RefreshBatchResult(
            runId = "test-run",
            results = results,
            aggregate = deriveRefreshBatchAggregate(results)
        )
    }

    private fun schedulerPrefs() =
        context.getSharedPreferences("refresh_scheduler_state", Context.MODE_PRIVATE)

    private class RecordingGateway(
        private val events: MutableList<String>
    ) : RefreshGateway {
        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
            events += "refresh:${trigger.name}"
            return batch(emptyList())
        }

        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
            error("not used")

        override fun invalidate(accountId: String) = Unit
    }

    private class FailingGateway(
        private val events: MutableList<String>,
        private val failure: RuntimeException
    ) : RefreshGateway {
        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
            events += "refresh:${trigger.name}"
            throw failure
        }

        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
            error("not used")

        override fun invalidate(accountId: String) = Unit
    }

    private class CancellingGateway(
        private val events: MutableList<String>
    ) : RefreshGateway {
        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
            events += "refresh:${trigger.name}"
            awaitCancellation()
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
