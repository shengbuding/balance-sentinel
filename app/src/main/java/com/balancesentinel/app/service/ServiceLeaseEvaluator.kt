package com.balancesentinel.app.service

import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity

/** Pure state projection for monitoring UI and health checks. */
object ServiceLeaseEvaluator {
    fun evaluate(
        state: MonitoringStateEntity,
        now: Long,
        processSessionId: String,
    ): MonitoringObservedState = MonitoringObservedState.STOPPED
}
