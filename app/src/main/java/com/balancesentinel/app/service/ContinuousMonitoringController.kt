package com.balancesentinel.app.service

import androidx.room.withTransaction
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionDao
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionEndReason
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionEntity
import com.balancesentinel.app.data.local.monitoring.MonitoringStateDao
import java.util.UUID

/** Coordinates bounded foreground sessions and their singleton projection. */
class ContinuousMonitoringController(
    private val database: WalletDatabase,
    private val processSessionId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val leaseDurationMillis: Long = MonitoringStateStore.DEFAULT_LEASE_MILLIS
) {
    private val sessions: MonitoringSessionDao = database.monitoringSessionDao()
    private val state: MonitoringStateDao = database.monitoringStateDao()

    suspend fun start(at: Long = now()): MonitoringSessionEntity? {
        var started: MonitoringSessionEntity? = null
        database.withTransaction {
            state.getOrCreate(at)
            state.setDesiredAndState(true, MonitoringObservedState.STARTING, "USER_STARTED", at)
            sessions.getOpenForProcess(processSessionId)?.let {
                state.projectSessionStart(it.id, processSessionId, it.startedAt)
                state.renewDesiredLease(processSessionId, at + leaseDurationMillis, at)
                started = it
                return@withTransaction
            }
            // Conservative process recovery happens before attempting a new unique active slot.
            val openBeforeRecovery = sessions.listOpen()
            sessions.endOpenForRecovery(processSessionId, at)
            openBeforeRecovery
                .filter { it.processSessionId != processSessionId }
                .forEach { recovered ->
                    state.projectSessionEnd(
                        recovered.id,
                        at,
                        MonitoringObservedState.ABNORMAL,
                        MonitoringSessionEndReason.PROCESS_RECOVERY.name
                    )
                }
            val session = MonitoringSessionEntity(
                id = UUID.randomUUID().toString(),
                processSessionId = processSessionId,
                startedAt = at,
                activeSlot = MonitoringSessionEntity.DATA_SYNC_SLOT
            )
            sessions.insertStart(session)
            state.projectSessionStart(session.id, processSessionId, at)
            state.renewDesiredLease(processSessionId, at + leaseDurationMillis, at)
            started = session
        }
        return started
    }

    suspend fun heartbeat(at: Long = now()): Boolean =
        database.withTransaction {
            state.getOrCreate(at)
            state.renewDesiredLease(processSessionId, at + leaseDurationMillis, at) > 0
        }

    suspend fun stop(
        reason: MonitoringSessionEndReason = MonitoringSessionEndReason.USER_STOPPED,
        at: Long = now()
    ) {
        database.withTransaction {
            val current = sessions.getOpenForProcess(processSessionId)
                ?: sessions.getOpenDataSync()
            if (current != null) {
                sessions.endCurrent(current.id, at, reason)
                state.projectSessionEnd(
                    current.id,
                    at,
                    MonitoringObservedState.STOPPED,
                    reason.name
                )
            }
            state.setDesiredAndState(false, MonitoringObservedState.STOPPED, reason.name, at)
        }
    }

    suspend fun onPlatformTimeout(at: Long = now()) {
        database.withTransaction {
            val current = sessions.getOpenForProcess(processSessionId)
                ?: sessions.getOpenDataSync()
            if (current != null) {
                sessions.endCurrent(current.id, at, MonitoringSessionEndReason.PLATFORM_TIMEOUT)
                state.projectSessionEnd(
                    current.id,
                    at,
                    MonitoringObservedState.PLATFORM_LIMITED,
                    MonitoringSessionEndReason.PLATFORM_TIMEOUT.name
                )
            }
            state.setDesiredAndState(
                desired = true,
                observedState = MonitoringObservedState.PLATFORM_LIMITED,
                reason = MonitoringSessionEndReason.PLATFORM_TIMEOUT.name,
                updatedAt = at
            )
        }
    }

    suspend fun observedState(at: Long = now()): MonitoringObservedState {
        val snapshot = state.getOrCreate(at)
        return ServiceLeaseEvaluator.evaluate(snapshot, at, processSessionId)
    }
}
