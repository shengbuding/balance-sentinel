package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.data.repository.CleanupScheduler
import com.balancesentinel.app.data.repository.MidnightScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 午夜日汇总接收器。
 * 每天 00:00 触发：聚合昨日 RawRecords → DailySummary，只删除已归档的旧记录。
 */
class MidnightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MidnightScheduler.ACTION_MIDNIGHT) return

        Logger.i("MidnightReceiver", "Midnight aggregation triggered")

        // WakeLock 防止 CPU 在聚合期间休眠
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MidnightReceiver:aggregate")
        wl.setReferenceCounted(false)
        try { wl.acquire(60_000L) } catch (_: Exception) {}

        try {
            // runBlocking 确保聚合完成后才返回，避免进程被系统提前杀死
            runBlocking {
                CleanupScheduler.runCleanup(context)
            }
            Logger.i("MidnightReceiver", "Cleanup completed")
        } catch (e: Exception) {
            Logger.e("MidnightReceiver", "Aggregation failed", e)
        } finally {
            MidnightScheduler.schedule(context)
            try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
        }
    }
}
