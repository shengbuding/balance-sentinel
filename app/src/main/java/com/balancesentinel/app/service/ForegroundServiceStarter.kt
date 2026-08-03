package com.balancesentinel.app.service

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.STARTUP_GRACE_MS
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.receiver.KeepAliveReceiver

sealed interface ServiceStartResult {
    data object Started : ServiceStartResult
    data class Deferred(val retryAt: Long, val reason: String) : ServiceStartResult
    data class Failed(val reason: String) : ServiceStartResult
}

enum class ServiceStartDiagnosticReason {
    START_NOT_ALLOWED,
    SECURITY_REJECTED,
    START_FAILED
}

data class ServiceStartDiagnostic(
    val reason: ServiceStartDiagnosticReason,
    val retryAt: Long? = null
)

fun interface ServiceStarter {
    fun start(context: Context): ServiceStartResult
}

fun interface ServiceStartRequestMarker {
    fun mark(context: Context, requestedAt: Long)
}

fun interface ForegroundServiceLauncher {
    fun launch(context: Context, intent: Intent)
}

fun interface ServiceStartRetryScheduler {
    fun schedule(context: Context, retryAt: Long)
}

fun interface ServiceStartDiagnosticSink {
    fun record(context: Context, diagnostic: ServiceStartDiagnostic)
}

class ForegroundServiceStarter(
    private val now: () -> Long = System::currentTimeMillis,
    private val requestMarker: ServiceStartRequestMarker = ServiceStartRequestMarker { context, requestedAt ->
        RefreshScheduler.markStartRequested(context, requestedAt)
    },
    private val launcher: ForegroundServiceLauncher = ForegroundServiceLauncher { context, intent ->
        ContextCompat.startForegroundService(context, intent)
    },
    private val retryScheduler: ServiceStartRetryScheduler = AlarmServiceStartRetryScheduler,
    private val diagnosticSink: ServiceStartDiagnosticSink = RefreshLogServiceStartDiagnosticSink
) : ServiceStarter {

    override fun start(context: Context): ServiceStartResult {
        val requestedAt = now()
        requestMarker.mark(context, requestedAt)
        val serviceIntent = Intent(context, BalanceRefreshService::class.java)
        return try {
            launcher.launch(context, serviceIntent)
            ServiceStartResult.Started
        } catch (_: ForegroundServiceStartNotAllowedException) {
            val retryAt = requestedAt + STARTUP_GRACE_MS + 1L
            retryScheduler.schedule(context, retryAt)
            diagnosticSink.record(
                context,
                ServiceStartDiagnostic(ServiceStartDiagnosticReason.START_NOT_ALLOWED, retryAt)
            )
            ServiceStartResult.Deferred(retryAt, REASON_START_NOT_ALLOWED)
        } catch (_: SecurityException) {
            diagnosticSink.record(
                context,
                ServiceStartDiagnostic(ServiceStartDiagnosticReason.SECURITY_REJECTED)
            )
            ServiceStartResult.Failed(REASON_SECURITY_REJECTED)
        } catch (_: RuntimeException) {
            diagnosticSink.record(
                context,
                ServiceStartDiagnostic(ServiceStartDiagnosticReason.START_FAILED)
            )
            ServiceStartResult.Failed(REASON_START_FAILED)
        }
    }

    companion object {
        const val REASON_START_NOT_ALLOWED = "foreground_service_start_not_allowed"
        const val REASON_SECURITY_REJECTED = "foreground_service_security_rejected"
        const val REASON_START_FAILED = "foreground_service_start_failed"
    }
}

private object AlarmServiceStartRetryScheduler : ServiceStartRetryScheduler {
    private const val REQUEST_CODE = 202

    override fun schedule(context: Context, retryAt: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val retryIntent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = KeepAliveReceiver.ACTION_SERVICE_START_RETRY
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pending)
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryAt, pending)
        } catch (_: SecurityException) {
            alarm.set(AlarmManager.RTC_WAKEUP, retryAt, pending)
        }
    }
}

private object RefreshLogServiceStartDiagnosticSink : ServiceStartDiagnosticSink {
    override fun record(context: Context, diagnostic: ServiceStartDiagnostic) {
        val message = when (diagnostic.reason) {
            ServiceStartDiagnosticReason.START_NOT_ALLOWED -> "foreground_service_start_deferred"
            ServiceStartDiagnosticReason.SECURITY_REJECTED -> "foreground_service_start_security_rejected"
            ServiceStartDiagnosticReason.START_FAILED -> "foreground_service_start_failed"
        }
        Logger.w("ServiceStarter", message)
        try {
            val now = System.currentTimeMillis()
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
            Logger.w("ServiceStarter", "foreground_service_diagnostic_write_failed")
        }
    }
}
