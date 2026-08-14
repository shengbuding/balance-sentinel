package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStarter
import com.balancesentinel.app.work.MidnightMaintenanceDependencies
import com.balancesentinel.app.work.MidnightWorkPolicy
import com.balancesentinel.app.work.MidnightWorkSchedulingGate
import com.balancesentinel.app.work.MidnightWorkScheduler
import com.balancesentinel.app.work.RefreshWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun interface RefreshWorkReconcileDelegate {
    suspend fun reconcile(context: Context)
}

/** Reconciles persistent work after boot without violating Android 15 FGS rules. */
class BootReceiver(
    /** Kept as a source-compatible seam; Android 15 boot never starts this service. */
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
    },
    private val refreshWorkReconcileDelegate: RefreshWorkReconcileDelegate =
        RefreshWorkReconcileDelegate { context ->
            RefreshWorkScheduler().reconcileFromRepository(context)
        }
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        runCatching { workReconcileDelegate.reconcile(context, MidnightWorkPolicy.KEEP) }
            .onFailure { error ->
                Logger.w("BootReceiver", "midnight_reconcile_delegate_failed", error)
            }

        val pending = runCatching { goAsync() }.getOrNull()
        if (pending == null) {
            Logger.w("BootReceiver", "boot_refresh_reconcile_deferred_no_pending_result")
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                refreshWorkReconcileDelegate.reconcile(context.applicationContext)
                Logger.i("BootReceiver", "boot_refresh_work_reconciled")
            } catch (error: Throwable) {
                Logger.w("BootReceiver", "boot_refresh_work_reconcile_failed", error)
            } finally {
                pending.finish()
            }
        }
    }
}
