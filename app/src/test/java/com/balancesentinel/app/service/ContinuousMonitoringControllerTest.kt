package com.balancesentinel.app.service

import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousMonitoringControllerTest {
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
