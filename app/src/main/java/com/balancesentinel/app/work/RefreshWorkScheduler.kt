package com.balancesentinel.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
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
        if (backgroundIntervalSeconds == null) {
            runtime.cancelUnique(context, PERIODIC_WORK_NAME)
            runtime.cancelAllRetries(context)
            return
        }

        // WorkManager's minimum periodic interval is 15 minutes. A shorter
        // cadence belongs to an explicitly active foreground session and is
        // never encoded as an invalid periodic request.
        val effectiveInterval = backgroundIntervalSeconds
            .coerceAtLeast(MIN_BACKGROUND_INTERVAL_SECONDS)
        runtime.enqueuePeriodic(
            context,
            PeriodicWorkSpec(
                uniqueName = PERIODIC_WORK_NAME,
                intervalSeconds = effectiveInterval,
                requiresNetwork = true,
                input = mapOf(KEY_INTERVAL_SECONDS to effectiveInterval.toString())
            )
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
                )
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

    companion object {
        const val PERIODIC_WORK_NAME = "refresh-periodic"
        const val RETRY_WORK_PREFIX = "refresh-retry-"
        const val RETRY_WORK_TAG = "refresh-retry"
        const val KEY_INTERVAL_SECONDS = "refresh_interval_seconds"
        const val MIN_BACKGROUND_INTERVAL_SECONDS = 900L
        const val MAX_RETRY_DELAY_MILLIS = 15 * 60_000L
        val LEGACY_WIDGET_ALARM_REQUEST_CODES: IntArray = intArrayOf(
            StaticWidgetProvider.LEGACY_WIDGET_ALARM_REQUEST_CODE
        )

        fun retryWorkName(accountId: String): String = RETRY_WORK_PREFIX + accountId
    }
}
