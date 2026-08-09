package com.balancesentinel.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.balancesentinel.app.widget.StaticWidgetProvider
import com.balancesentinel.app.widget.WidgetRefreshIntents

/** Test-first seam for the unified refresh scheduler. */
class RefreshWorkScheduler(
    private val runtime: WorkRuntime = DefaultWorkRuntime
) {
    fun reconcile(
        context: Context,
        backgroundIntervalSeconds: Long,
        foregroundSessionActive: Boolean = false,
        widgetEnabled: Boolean = false
    ) {
        val interval = if (foregroundSessionActive) {
            backgroundIntervalSeconds
        } else {
            backgroundIntervalSeconds.coerceAtLeast(MIN_BACKGROUND_INTERVAL_SECONDS)
        }
        runtime.enqueuePeriodic(
            context,
            PeriodicWorkSpec(
                uniqueName = PERIODIC_WORK_NAME,
                intervalSeconds = interval,
                requiresNetwork = true
            )
        )
        cancelLegacyWidgetAlarm(context)
    }

    private fun cancelLegacyWidgetAlarm(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            StaticWidgetProvider.LEGACY_WIDGET_ALARM_REQUEST_CODE,
            WidgetRefreshIntents.watchdog(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pending)
    }

    companion object {
        const val PERIODIC_WORK_NAME = "refresh-periodic"
        const val KEY_INTERVAL_SECONDS = "refresh_interval_seconds"
        const val MIN_BACKGROUND_INTERVAL_SECONDS = 900L
    }
}