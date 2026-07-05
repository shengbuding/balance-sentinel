package com.balancesentinel.app.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import java.util.Calendar

/**
 * 午夜日汇总闹钟调度器。
 * 每天 00:00 触发 [com.balancesentinel.app.receiver.MidnightReceiver] 执行日汇总聚合。
 *
 * 模式参照 [com.balancesentinel.app.receiver.KeepAliveReceiver] 的 companion object。
 */
object MidnightScheduler {

    const val ACTION_MIDNIGHT = "com.balancesentinel.app.MIDNIGHT_AGGREGATE"
    private const val REQUEST_CODE = 301

    /**
     * 调度明天 00:00 的闹钟。每次调用覆盖旧闹钟，不会堆积。
     * 在 App 启动 / 闹钟触发后调用。
     */
    fun schedule(context: Context) {
        try {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(ACTION_MIDNIGHT).apply { setPackage(context.packageName) }
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)

            // 计算明天 00:00:00 的时间戳
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val triggerTime = calendar.timeInMillis

            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, triggerTime, pending)
            } catch (_: SecurityException) {
                alarm.set(AlarmManager.RTC, triggerTime, pending)
            }
        } catch (_: Exception) {
            // 闹钟调度失败不应影响主流程
        }
    }

    /**
     * 取消午夜闹钟。
     */
    fun cancel(context: Context) {
        try {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(ACTION_MIDNIGHT).apply { setPackage(context.packageName) }
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)
        } catch (e: Exception) { Logger.w("MidnightScheduler", "schedule failed", e) }
    }
}
