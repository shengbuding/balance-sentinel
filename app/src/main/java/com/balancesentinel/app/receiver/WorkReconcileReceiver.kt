package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.work.MidnightWorkPolicy
import com.balancesentinel.app.work.MidnightWorkScheduler

/** Injectable entry point for process/package/time-zone recovery reconciliation. */
fun interface WorkReconcileDelegate {
    fun reconcile(context: Context)

    /**
     * Policy-aware entry point. Legacy delegates can still handle KEEP, but
     * must not silently downgrade a timezone replacement to KEEP.
     */
    fun reconcile(context: Context, policy: MidnightWorkPolicy) {
        if (policy == MidnightWorkPolicy.KEEP) {
            reconcile(context)
        } else {
            throw UnsupportedOperationException(
                "WorkReconcileDelegate must override the policy-aware reconcile method"
            )
        }
    }
}

class WorkReconcileReceiver(
    private val delegate: WorkReconcileDelegate = object : WorkReconcileDelegate {
        override fun reconcile(context: Context) {
            reconcile(context, MidnightWorkPolicy.KEEP)
        }

        override fun reconcile(context: Context, policy: MidnightWorkPolicy) {
            runCatching {
                MidnightWorkScheduler().reconcile(context, policy = policy)
            }.onFailure { error ->
                Logger.w("WorkReconcileReceiver", "midnight_reconcile_failed", error)
            }
        }
    }
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECONCILE_ACTIONS) return
        val policy = if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            MidnightWorkPolicy.REPLACE
        } else {
            MidnightWorkPolicy.KEEP
        }
        runCatching { delegate.reconcile(context, policy) }
            .onFailure { error ->
                Logger.w("WorkReconcileReceiver", "midnight_reconcile_failed", error)
            }
    }

    companion object {
        val RECONCILE_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
