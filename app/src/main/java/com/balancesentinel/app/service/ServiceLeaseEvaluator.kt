package com.balancesentinel.app.service

import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity

/** Pure state projection for monitoring UI and health checks. */
object ServiceLeaseEvaluator {
    fun evaluate(
        state: MonitoringStateEntity,
        now: Long,
        processSessionId: String,
    ): MonitoringObservedState {
        if (!state.desired) return MonitoringObservedState.STOPPED
        if (state.observedState == MonitoringObservedState.PLATFORM_LIMITED ||
            state.observedState == MonitoringObservedState.PAUSED
        ) return state.observedState
        val lease = state.processSessionId?.let { id ->
            state.leaseExpiresAt?.let { expiry -> ServiceLease(id, expiry) }
        }
        return when {
            lease == null -> if (state.observedState == MonitoringObservedState.STARTING) {
                MonitoringObservedState.STARTING
            } else MonitoringObservedState.ABNORMAL
            lease.isFresh(now, processSessionId) -> MonitoringObservedState.RUNNING
            else -> MonitoringObservedState.ABNORMAL
        }
    }
}
