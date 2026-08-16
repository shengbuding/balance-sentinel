package com.balancesentinel.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.balancesentinel.app.data.refresh.RefreshRuntime
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.service.MonitoringStateStore
import com.balancesentinel.app.service.PersistentBalanceNotificationPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-publishes the retained balance notification on a short alarm cadence.
 *
 * This receiver deliberately never starts [com.balancesentinel.app.service.BalanceRefreshService].
 * Android 15 restricts background dataSync foreground-service launches, while
 * posting a cached notification is legal and keeps the user-visible status
 * recoverable after an OEM removes the detached notification.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                reconcile(context.applicationContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logger.w(TAG, "keep_alive_reconcile_failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun reconcile(context: Context) {
        if (!KeepAliveReceiverDependencies.desiredReader(context)) {
            cancel(context)
            return
        }

        // Re-arm before publishing so a transient cache/settings failure never
        // leaves the watchdog without a subsequent recovery attempt.
        schedule(context)
        try {
            KeepAliveReceiverDependencies.notificationPublisher(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Logger.w(TAG, "keep_alive_notification_publish_failed", error)
        }
    }

    companion object {
        const val ACTION_KEEP_ALIVE = "com.balancesentinel.app.KEEPALIVE_PING"

        private const val REQUEST_CODE = 201
        private const val STANDARD_INTERVAL_MILLIS = 120_000L
        private const val AGGRESSIVE_OEM_INTERVAL_MILLIS = 90_000L
        private val AGGRESSIVE_OEMS = setOf("oneplus", "oppo", "vivo", "xiaomi")
        private val SUPPORTED_ACTIONS = setOf(
            ACTION_KEEP_ALIVE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        private const val TAG = "KeepAlive"

        fun schedule(context: Context) {
            val appContext = context.applicationContext
            val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pending = pendingIntent(appContext)
            alarm.cancel(pending)

            val triggerAt = System.currentTimeMillis() + intervalMillis()
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } else {
                    // The user can grant SCHEDULE_EXACT_ALARM from capability
                    // settings. Until then retain a best-effort wakeup path.
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                }
            } catch (error: SecurityException) {
                Logger.w(TAG, "keep_alive_alarm_permission_denied", error)
                try {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } catch (fallbackError: Throwable) {
                    Logger.w(TAG, "keep_alive_alarm_schedule_failed", fallbackError)
                }
            } catch (error: Throwable) {
                Logger.w(TAG, "keep_alive_alarm_schedule_failed", error)
            }
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try {
                alarm.cancel(pendingIntent(appContext))
            } catch (error: Throwable) {
                Logger.w(TAG, "keep_alive_alarm_cancel_failed", error)
            }
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_KEEP_ALIVE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun intervalMillis(): Long = if (
            Build.MANUFACTURER.orEmpty().lowercase() in AGGRESSIVE_OEMS
        ) {
            AGGRESSIVE_OEM_INTERVAL_MILLIS
        } else {
            STANDARD_INTERVAL_MILLIS
        }
    }
}

internal object KeepAliveReceiverDependencies {
    var desiredReader: suspend (Context) -> Boolean = { context ->
        MonitoringStateStore.from(context).get().desired
    }
    var notificationPublisher: suspend (Context) -> Unit = { context ->
        PersistentBalanceNotificationPublisher.from(context)
            .publishCached(RefreshRuntime.from(context))
    }

    fun reset() {
        desiredReader = { context -> MonitoringStateStore.from(context).get().desired }
        notificationPublisher = { context ->
            PersistentBalanceNotificationPublisher.from(context)
                .publishCached(RefreshRuntime.from(context))
        }
    }
}
