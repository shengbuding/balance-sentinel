package com.balancesentinel.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter

/** Alarm-backed health check for the refresh foreground service. */
class KeepAliveReceiver(
    private val serviceStarter: ServiceStarter = ForegroundServiceStarter()
) : BroadcastReceiver() {

    companion object {
        const val ACTION_KEEPALIVE = "com.balancesentinel.app.KEEPALIVE_PING"
        const val ACTION_SERVICE_START_RETRY = "com.balancesentinel.app.SERVICE_START_RETRY"

        private const val KEEPALIVE_INTERVAL_DEFAULT = 120_000L
        private const val KEEPALIVE_INTERVAL_OEM = 90_000L
        private const val REQUEST_CODE = 201
        private val AGGRESSIVE_OEMS = setOf("oneplus", "oppo", "vivo", "xiaomi")

        fun schedule(context: Context) {
            try {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val pending = keepalivePendingIntent(context)
                alarm.cancel(pending)
                val interval = if (Build.MANUFACTURER.lowercase() in AGGRESSIVE_OEMS) {
                    KEEPALIVE_INTERVAL_OEM
                } else {
                    KEEPALIVE_INTERVAL_DEFAULT
                }
                val triggerAt = System.currentTimeMillis() + interval
                try {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } catch (_: SecurityException) {
                    alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                }
            } catch (_: RuntimeException) {
                Logger.w("KeepAlive", "keepalive_schedule_failed")
            }
        }

        fun cancel(context: Context) {
            try {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                alarm.cancel(keepalivePendingIntent(context))
            } catch (_: RuntimeException) {
                Logger.w("KeepAlive", "keepalive_cancel_failed")
            }
        }

        private fun keepalivePendingIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION_KEEPALIVE).apply { setPackage(context.packageName) }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SERVICE_START_RETRY) {
            serviceStarter.start(context)
            return
        }
        if (intent.action != ACTION_KEEPALIVE) return

        val now = System.currentTimeMillis()
        if (!RefreshScheduler.shouldRestart(context, now)) return

        Logger.w("KeepAlive", "service_restart_requested")
        RefreshScheduler.recordRestart(context)
        val message = when (serviceStarter.start(context)) {
            ServiceStartResult.Started -> "keepalive_service_started"
            is ServiceStartResult.Deferred -> "keepalive_service_start_deferred"
            is ServiceStartResult.Failed -> "keepalive_service_start_failed"
        }
        try {
            RefreshLogStore.addEntry(
                context,
                RefreshLogEntry(
                    id = now,
                    type = RefreshLogType.WATCHDOG,
                    timestamp = now,
                    message = message,
                    alarmMethod = "foreground_service"
                )
            )
        } catch (_: RuntimeException) {
            Logger.w("KeepAlive", "keepalive_diagnostic_write_failed")
        }
    }
}
