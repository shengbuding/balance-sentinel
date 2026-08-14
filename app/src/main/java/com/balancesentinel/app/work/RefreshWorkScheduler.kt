package com.balancesentinel.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.widget.StaticWidgetProvider
import com.balancesentinel.app.widget.WidgetRefreshIntents

/** Owns all recoverable periodic and per-account refresh work. */
class RefreshWorkScheduler(
    private val runtime: WorkRuntime = DefaultWorkRuntime
) {
    fun reconcile(
        context: Context,
        backgroundIntervalSeconds: Long?,
        foregroundSessionActive: Boolean = false,
        widgetEnabled: Boolean = false
    ) {
        cancelLegacyWidgetAlarms(context)
        val configuredInterval = backgroundIntervalSeconds?.coerceAtLeast(1L)
        if (configuredInterval == null) {
            runtime.cancelUnique(context, PERIODIC_WORK_NAME)
            runtime.cancelUnique(context, PROCESS_RECOVERY_WORK_NAME)
            runtime.cancelAllRetries(context)
            RefreshScheduler.clearExpectedState(context)
            RefreshScheduler.clearBackgroundPlan(context)
            return
        }

        val plan = planFor(configuredInterval)
        val previousPlan = RefreshScheduler.getBackgroundPlan(context)
        val scheduledAt = System.currentTimeMillis()
        val expectedAt = safeAddMillis(scheduledAt, plan.scheduledIntervalSeconds)
        cancelOppositePlan(context, plan.mode)
        if (plan.mode == BackgroundRefreshMode.RECOVERY_CHAIN) {
            // PeriodicWorkRequest cannot represent a user cadence below 15
            // minutes. A unique one-shot chain is persistent across process
            // death and device reboot; each worker schedules the next tick.
            runtime.enqueueOneShot(
                context,
                OneShotWorkSpec(
                    uniqueName = PROCESS_RECOVERY_WORK_NAME,
                    delayMillis = delayMillisFor(plan.scheduledIntervalSeconds),
                    requiresNetwork = true,
                    policy = if (
                        previousPlan?.intervalSeconds == plan.scheduledIntervalSeconds &&
                        previousPlan.mode == plan.mode.diagnosticName
                    ) {
                        OneShotWorkPolicy.KEEP
                    } else {
                        OneShotWorkPolicy.REPLACE
                    },
                    input = mapOf(
                        KEY_INTERVAL_SECONDS to plan.scheduledIntervalSeconds.toString(),
                        KEY_CONTINUOUS_RECOVERY to "true"
                    )
                )
            )
        } else {
            runtime.enqueuePeriodic(
                context,
                PeriodicWorkSpec(
                    uniqueName = PERIODIC_WORK_NAME,
                    intervalSeconds = effectiveBackgroundIntervalSeconds(configuredInterval),
                    requiresNetwork = true,
                    input = mapOf(KEY_INTERVAL_SECONDS to plan.scheduledIntervalSeconds.toString())
                )
            )
        }
        RefreshScheduler.recordBackgroundPlan(
            context,
            plan.scheduledIntervalSeconds,
            plan.mode.diagnosticName
        )
        RefreshScheduler.recordSchedule(
            context,
            plan.scheduledIntervalSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            expectedAt,
            plan.mode.diagnosticName
        )
    }

    /** Reconciles from the Room source of truth after process/boot recovery. */
    suspend fun reconcileFromRepository(context: Context): Long? {
        val interval = SettingsRepositoryProvider.get(context).readSnapshot()
            .effectiveBackgroundCadenceSeconds?.toLong()
        reconcile(context, interval)
        return interval
    }

    /** Continues a short-cadence chain without cancelling the running worker. */
    fun continueRecovery(context: Context, intervalSeconds: Long?) {
        val configuredInterval = intervalSeconds?.coerceAtLeast(1L)
        if (configuredInterval == null) {
            reconcile(context, null)
            return
        }
        val plan = planFor(configuredInterval)
        if (plan.mode != BackgroundRefreshMode.RECOVERY_CHAIN) {
            reconcile(context, configuredInterval)
            return
        }
        val scheduledAt = System.currentTimeMillis()
        runtime.enqueueOneShot(
            context,
            OneShotWorkSpec(
                uniqueName = PROCESS_RECOVERY_WORK_NAME,
                delayMillis = delayMillisFor(plan.scheduledIntervalSeconds),
                requiresNetwork = true,
                policy = OneShotWorkPolicy.APPEND_OR_REPLACE,
                input = mapOf(
                    KEY_INTERVAL_SECONDS to plan.scheduledIntervalSeconds.toString(),
                    KEY_CONTINUOUS_RECOVERY to "true"
                )
            )
        )
        RefreshScheduler.recordBackgroundPlan(
            context,
            plan.scheduledIntervalSeconds,
            plan.mode.diagnosticName
        )
        RefreshScheduler.recordSchedule(
            context,
            plan.scheduledIntervalSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            safeAddMillis(scheduledAt, plan.scheduledIntervalSeconds),
            plan.mode.diagnosticName
        )
    }

    fun scheduleRetry(context: Context, schedule: RetrySchedule) {
        runtime.enqueueOneShot(
            context,
            OneShotWorkSpec(
                uniqueName = retryWorkName(schedule.accountId),
                delayMillis = schedule.delayMillis.coerceIn(0L, MAX_RETRY_DELAY_MILLIS),
                attempt = schedule.attempt,
                input = mapOf(
                    RefreshWorker.KEY_ACCOUNT_ID to schedule.accountId,
                    RefreshWorker.KEY_ATTEMPT to schedule.attempt.toString()
                ),
                requiresNetwork = true
            )
        )
    }

    fun cancelRetries(context: Context, accountId: String) {
        runtime.cancelUnique(context, retryWorkName(accountId))
    }

    private fun cancelLegacyWidgetAlarms(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        LEGACY_WIDGET_ALARM_REQUEST_CODES.forEach { requestCode ->
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                WidgetRefreshIntents.watchdog(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)
        }
    }

    private fun cancelOppositePlan(context: Context, mode: BackgroundRefreshMode) {
        when (mode) {
            BackgroundRefreshMode.RECOVERY_CHAIN ->
                runtime.cancelUnique(context, PERIODIC_WORK_NAME)
            BackgroundRefreshMode.PERIODIC ->
                runtime.cancelUnique(context, PROCESS_RECOVERY_WORK_NAME)
        }
    }

    private fun delayMillisFor(intervalSeconds: Long): Long =
        intervalSeconds.coerceAtMost(MAX_DELAY_SECONDS) * 1000L

    private fun safeAddMillis(startMillis: Long, intervalSeconds: Long): Long {
        val delay = delayMillisFor(intervalSeconds)
        return if (Long.MAX_VALUE - startMillis < delay) Long.MAX_VALUE else startMillis + delay
    }

    companion object {
        const val PERIODIC_WORK_NAME = "refresh-periodic"
        const val PROCESS_RECOVERY_WORK_NAME = "refresh-process-recovery"
        const val RETRY_WORK_PREFIX = "refresh-retry-"
        const val RETRY_WORK_TAG = "refresh-retry"
        const val KEY_INTERVAL_SECONDS = "refresh_interval_seconds"
        const val KEY_CONTINUOUS_RECOVERY = "continuous_recovery"
        const val MIN_BACKGROUND_INTERVAL_SECONDS = 900L
        const val MAX_RETRY_DELAY_MILLIS = 15 * 60_000L
        private const val MAX_DELAY_SECONDS = Long.MAX_VALUE / 1000L
        val LEGACY_WIDGET_ALARM_REQUEST_CODES: IntArray = intArrayOf(
            StaticWidgetProvider.LEGACY_WIDGET_ALARM_REQUEST_CODE
        )

        fun retryWorkName(accountId: String): String = RETRY_WORK_PREFIX + accountId

        fun effectiveBackgroundIntervalSeconds(configuredSeconds: Long): Long =
            configuredSeconds.coerceAtLeast(MIN_BACKGROUND_INTERVAL_SECONDS)

        fun planFor(configuredSeconds: Long): BackgroundRefreshPlan {
            val sanitized = configuredSeconds.coerceAtLeast(1L)
            return if (sanitized < MIN_BACKGROUND_INTERVAL_SECONDS) {
                BackgroundRefreshPlan(sanitized, BackgroundRefreshMode.RECOVERY_CHAIN)
            } else {
                BackgroundRefreshPlan(sanitized, BackgroundRefreshMode.PERIODIC)
            }
        }
    }
}

enum class BackgroundRefreshMode(val diagnosticName: String) {
    PERIODIC("work_manager_periodic"),
    RECOVERY_CHAIN("work_manager_recovery_chain")
}

data class BackgroundRefreshPlan(
    val scheduledIntervalSeconds: Long,
    val mode: BackgroundRefreshMode
)
