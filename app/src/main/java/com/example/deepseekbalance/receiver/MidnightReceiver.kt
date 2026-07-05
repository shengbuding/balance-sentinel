package com.example.deepseekbalance.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.deepseekbalance.data.util.Logger
import com.example.deepseekbalance.data.repository.CleanupScheduler
import com.example.deepseekbalance.data.repository.MidnightScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 午夜日汇总接收器。
 * 每天 00:00 触发：聚合昨日 RawRecords → DailySummary，只删除已归档的旧记录。
 */
class MidnightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MidnightScheduler.ACTION_MIDNIGHT) return

        Logger.i("MidnightReceiver", "Midnight aggregation triggered")
        try {
            CoroutineScope(Dispatchers.IO).launch {
                CleanupScheduler.runCleanup(context)
                Logger.i("MidnightReceiver", "Cleanup completed")
            }
        } catch (e: Exception) {
            Logger.e("MidnightReceiver", "Aggregation failed", e)
        } finally {
            MidnightScheduler.schedule(context)
        }
    }
}
