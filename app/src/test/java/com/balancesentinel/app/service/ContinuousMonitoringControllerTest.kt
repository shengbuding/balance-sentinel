package com.balancesentinel.app.service

import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionEndReason
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContinuousMonitoringControllerTest {
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `system recovery never enables monitoring that the user disabled`() = runTest {
        val controller = ContinuousMonitoringController(database, "process", now = { 100L })

        assertNull(controller.start(at = 100L, userInitiated = false))
        assertFalse(database.monitoringStateDao().getOrCreate(100L).desired)
        assertTrue(database.monitoringSessionDao().listOpen().isEmpty())
    }

    @Test
    fun `service destruction ends the session but preserves desired intent`() = runTest {
        val controller = ContinuousMonitoringController(database, "process", now = { 100L })
        val session = controller.start(at = 100L, userInitiated = true)
        assertNotNull(session)

        controller.stop(
            reason = MonitoringSessionEndReason.SERVICE_DESTROYED,
            at = 200L,
            preserveDesired = true
        )

        val state = database.monitoringStateDao().getOrCreate(200L)
        assertTrue(state.desired)
        assertEquals(MonitoringObservedState.ABNORMAL, state.observedState)
        assertTrue(database.monitoringSessionDao().listOpen().isEmpty())
    }

    @Test
    fun `new process recovers the old open session without clearing desired`() = runTest {
        val oldController = ContinuousMonitoringController(database, "old", now = { 100L })
        val oldSession = oldController.start(at = 100L, userInitiated = true)
        assertNotNull(oldSession)

        val newController = ContinuousMonitoringController(database, "new", now = { 200L })
        val newSession = newController.start(at = 200L, userInitiated = false)
        assertNotNull(newSession)

        val state = database.monitoringStateDao().getOrCreate(200L)
        assertTrue(state.desired)
        assertEquals(MonitoringObservedState.RUNNING, state.observedState)
        assertEquals("new", state.processSessionId)
        assertEquals(
            MonitoringSessionEndReason.PROCESS_RECOVERY,
            database.monitoringSessionDao().get(oldSession!!.id)?.endReason
        )
    }

    @Test
    fun `desired false is stopped even with an old heartbeat`() {
        val state = MonitoringStateEntity(
            desired = false,
            observedState = MonitoringObservedState.RUNNING,
            processSessionId = "p",
            leaseExpiresAt = 999_999L,
            updatedAt = 1L
        )

        assertEquals(
            MonitoringObservedState.STOPPED,
            ServiceLeaseEvaluator.evaluate(state, now = 10L, processSessionId = "p")
        )
    }

    @Test
    fun `different process heartbeat is never fresh`() {
        val state = MonitoringStateEntity(
            desired = true,
            observedState = MonitoringObservedState.RUNNING,
            processSessionId = "old",
            leaseExpiresAt = 999_999L,
            updatedAt = 1L
        )

        assertEquals(
            MonitoringObservedState.ABNORMAL,
            ServiceLeaseEvaluator.evaluate(state, now = 10L, processSessionId = "new")
        )
    }

    @Test
    fun `expired lease transitions running to abnormal`() {
        val state = MonitoringStateEntity(
            desired = true,
            observedState = MonitoringObservedState.RUNNING,
            processSessionId = "p",
            leaseExpiresAt = 9L,
            updatedAt = 1L
        )

        assertEquals(
            MonitoringObservedState.ABNORMAL,
            ServiceLeaseEvaluator.evaluate(state, now = 10L, processSessionId = "p")
        )
    }
}
