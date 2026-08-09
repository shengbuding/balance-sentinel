package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter
import com.balancesentinel.app.work.MidnightMaintenanceDependencies
import com.balancesentinel.app.work.MidnightWorkPolicy
import com.balancesentinel.app.work.MidnightWorkSchedulingGate
import com.balancesentinel.app.work.MidnightWorkScheduler

/** Starts the refresh foreground service after boot and arms the keepalive. */
class BootReceiver(
    private val serviceStarter: ServiceStarter = ForegroundServiceStarter(),
    private val workReconcileDelegate: WorkReconcileDelegate = WorkReconcileDelegate { context ->
        runCatching {
            MidnightWorkSchedulingGate.withLock {
                MidnightWorkScheduler().reconcile(
                    context,
                    zoneId = MidnightMaintenanceDependencies.zoneIdProvider(),
                    policy = MidnightWorkPolicy.KEEP
                )
            }
        }.onFailure { error ->
            Logger.w("BootReceiver", "midnight_reconcile_failed", error)
        }
    }
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Logger.i("BootReceiver", "boot_completed_start_requested")
        when (serviceStarter.start(context)) {
            ServiceStartResult.Started -> Logger.i("BootReceiver", "boot_service_started")
            is ServiceStartResult.Deferred -> Logger.w("BootReceiver", "boot_service_start_deferred")
            is ServiceStartResult.Failed -> Logger.w("BootReceiver", "boot_service_start_failed")
        }
        KeepAliveReceiver.schedule(context)
        runCatching { workReconcileDelegate.reconcile(context, MidnightWorkPolicy.KEEP) }
            .onFailure { error ->
                Logger.w("BootReceiver", "midnight_reconcile_delegate_failed", error)
            }
    }
}
