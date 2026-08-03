package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStartResult
import com.balancesentinel.app.service.ServiceStarter

/** Starts the refresh foreground service after boot and arms the keepalive. */
class BootReceiver(
    private val serviceStarter: ServiceStarter = ForegroundServiceStarter()
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
    }
}
