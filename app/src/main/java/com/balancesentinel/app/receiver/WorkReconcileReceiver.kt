package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Injectable entry point for process/package/time-zone recovery reconciliation. */
fun interface WorkReconcileDelegate {
    fun reconcile(context: Context)
}

class WorkReconcileReceiver(
    private val delegate: WorkReconcileDelegate = WorkReconcileDelegate { }
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECONCILE_ACTIONS) return
        delegate.reconcile(context)
    }

    companion object {
        val RECONCILE_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}

