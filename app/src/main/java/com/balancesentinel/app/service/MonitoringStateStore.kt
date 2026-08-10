package com.balancesentinel.app.service

import android.content.Context
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateDao
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import java.util.UUID

/** Small repository around the singleton monitoring projection. */
class MonitoringStateStore(
    private val dao: MonitoringStateDao,
    private val now: () -> Long = System::currentTimeMillis,
    val processSessionId: String = UUID.randomUUID().toString()
) {
    suspend fun get(): MonitoringStateEntity = dao.getOrCreate(now())

    suspend fun setDesired(desired: Boolean, at: Long = now()) {
        dao.getOrCreate(at)
        dao.setDesiredAndState(
            desired = desired,
            observedState = if (desired) MonitoringObservedState.STARTING else MonitoringObservedState.STOPPED,
            reason = if (desired) "USER_STARTED" else "USER_STOPPED",
            updatedAt = at
        )
    }

    suspend fun renewLease(at: Long = now(), durationMillis: Long = DEFAULT_LEASE_MILLIS): Boolean {
        dao.getOrCreate(at)
        return dao.renewDesiredLease(processSessionId, at + durationMillis, at) > 0
    }

    suspend fun evaluate(at: Long = now()): MonitoringObservedState {
        val state = dao.getOrCreate(at)
        return ServiceLeaseEvaluator.evaluate(state, at, processSessionId)
    }

    companion object {
        const val DEFAULT_LEASE_MILLIS = 90_000L

        fun from(context: Context): MonitoringStateStore =
            MonitoringStateStore(WalletDatabaseProvider.get(context).monitoringStateDao())
    }
}
