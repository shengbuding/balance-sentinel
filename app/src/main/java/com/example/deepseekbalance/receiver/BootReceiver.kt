package com.example.deepseekbalance.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.deepseekbalance.service.BalanceRefreshService

/**
 * 开机自启 — 系统启动完成后，以非前台方式启动 Service。
 *
 * Android 12+ 限制后台启动前台 Service，因此使用 [startService]
 * （非 startForegroundService），由 Service 自己调用 startForeground()。
 * 系统允许在 BOOT_COMPLETED 后的短暂窗口内做此操作。
 *
 * 同时设定初始 keepalive 闹钟，确保即使 Service 启动后立即被冻结也能恢复。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                Log.i("BootReceiver", "BOOT_COMPLETED — starting service")
                val serviceIntent = Intent(context, BalanceRefreshService::class.java)
                context.startService(serviceIntent) // 非前台启动，Service 自行 promote
                // 设定初始 keepalive 闹钟 — Service 2 分钟内未上线则由 KeepAliveReceiver 拉起
                KeepAliveReceiver.schedule(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service", e)
            }
        }
    }
}
