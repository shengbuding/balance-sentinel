package com.balancesentinel.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.balancesentinel.app.data.repository.RefreshScheduler

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
    private val retryScheduler: ServiceStartRetryScheduler = ServiceStartRetryScheduler { _, _ -> Unit },
    private val diagnosticSink: ServiceStartDiagnosticSink = ServiceStartDiagnosticSink { _, _ -> Unit }
) : ServiceStarter {

    override fun start(context: Context): ServiceStartResult {
        val serviceIntent = Intent(context, BalanceRefreshService::class.java)
        return try {
            launcher.launch(context, serviceIntent)
            ServiceStartResult.Started
        } catch (_: RuntimeException) {
            ServiceStartResult.Failed(REASON_START_FAILED)
        }
    }

    companion object {
        const val REASON_START_NOT_ALLOWED = "foreground_service_start_not_allowed"
        const val REASON_SECURITY_REJECTED = "foreground_service_security_rejected"
        const val REASON_START_FAILED = "foreground_service_start_failed"
    }
}
