package com.balancesentinel.app.service

import android.content.Context
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateDao
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.DeepSeekApp
import java.util.UUID

/** Small repository around the singleton monitoring projection. */
class MonitoringStateStore(
    private val dao: MonitoringStateDao,
    private val now: () -> Long = System::currentTimeMillis,
    val processSessionId: String = UUID.randomUUID().toString(),
    private val legacyIntentProvider: () -> Boolean = { false }
) {
    suspend fun get(): MonitoringStateEntity {
        dao.get()?.let { existing ->
            // Room v1 created a default STOPPED row before the legacy intent
            // migration was added. Treat only that untouched placeholder as
            // migratable; an explicit USER_STOPPED decision must win forever.
            if (
                !existing.desired &&
                existing.observedState == MonitoringObservedState.STOPPED &&
                existing.stateReason == null &&
                runCatching { legacyIntentProvider() }.getOrDefault(false)
            ) {
                val migratedAt = now()
                dao.setDesiredAndState(
                    desired = true,
                    observedState = MonitoringObservedState.STARTING,
                    reason = LEGACY_RECOVERY_REASON,
                    updatedAt = migratedAt
                )
                return requireNotNull(dao.get())
            }
            return existing
        }

        // Versions before the Room monitoring projection started the
        // foreground service automatically. Preserve that established user
        // intent exactly once when the singleton row is first materialized;
        // an explicit USER_STOPPED row is never overwritten later.
        val legacyIntent = runCatching { legacyIntentProvider() }.getOrDefault(false)
        return dao.getOrCreate(
            updatedAt = now(),
            initialDesired = legacyIntent,
            initialReason = if (legacyIntent) LEGACY_RECOVERY_REASON else null
        )
    }

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
            MonitoringStateStore(
                WalletDatabaseProvider.get(context).monitoringStateDao(),
                processSessionId = (context.applicationContext as? DeepSeekApp)?.processSessionId
                    ?: UUID.randomUUID().toString(),
                legacyIntentProvider = {
                    RefreshScheduler.hasLegacyForegroundIntent(context)
                }
            )

        const val LEGACY_RECOVERY_REASON = "LEGACY_FOREGROUND_RECOVERY"
    }
}
