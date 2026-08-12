package com.balancesentinel.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.STARTUP_GRACE_MS
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.work.RefreshWorker

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

fun interface ForegroundServiceFallbackScheduler {
    fun enqueue(context: Context)
}

class ForegroundServiceStarter(
    private val now: () -> Long = System::currentTimeMillis,
    private val requestMarker: ServiceStartRequestMarker = ServiceStartRequestMarker { context, requestedAt ->
        RefreshScheduler.markStartRequested(context, requestedAt)
    },
    private val launcher: ForegroundServiceLauncher = ForegroundServiceLauncher { context, intent ->
        ContextCompat.startForegroundService(context, intent)
    },
    private val retryScheduler: ServiceStartRetryScheduler = ServiceStartRetryNoop,
    private val diagnosticSink: ServiceStartDiagnosticSink = RefreshLogServiceStartDiagnosticSink,
    private val fallbackScheduler: ForegroundServiceFallbackScheduler = WorkManagerForegroundFallbackScheduler,
    private val userInitiated: Boolean = false
) : ServiceStarter {

    override fun start(context: Context): ServiceStartResult {
        val requestedAt = now()
        requestMarker.mark(context, requestedAt)
        val serviceIntent = Intent(context, BalanceRefreshService::class.java)
            .putExtra(BalanceRefreshService.EXTRA_USER_INITIATED, userInitiated)
        return try {
            launcher.launch(context, serviceIntent)
            ServiceStartResult.Started
        } catch (_: ForegroundServiceStartNotAllowedException) {
            val retryAt = requestedAt + STARTUP_GRACE_MS + 1L
            retryScheduler.schedule(context, retryAt)
            fallbackScheduler.enqueue(context)
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

private object ServiceStartRetryNoop : ServiceStartRetryScheduler {
    override fun schedule(context: Context, retryAt: Long) = Unit
}

private object WorkManagerForegroundFallbackScheduler : ForegroundServiceFallbackScheduler {
    override fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "foreground-refresh-fallback",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().build()
        )
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
            com.balancesentinel.app.data.repository.appendRoomEvent(
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
