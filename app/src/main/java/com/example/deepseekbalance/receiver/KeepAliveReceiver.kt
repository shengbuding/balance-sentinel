package com.example.deepseekbalance.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.deepseekbalance.data.util.Logger
import com.example.deepseekbalance.data.model.RefreshLogEntry
import com.example.deepseekbalance.data.model.RefreshLogType
import com.example.deepseekbalance.data.repository.RefreshLogStore
import com.example.deepseekbalance.data.repository.RefreshScheduler
import com.example.deepseekbalance.service.BalanceRefreshService

/**
 * Dead-man's-switch keepalive 接收器 — OnePlus/ColorOS 冻结对抗。
 *
 * **工作原理**：
 * 1. [BalanceRefreshService] 每次刷新完成后调用 [schedule] 设定 2 分钟后的闹钟
 * 2. 如果 Service 正常运行，2 分钟内会再次刷新并重新设定闹钟（旧闹钟自动覆盖）
 * 3. 如果 Service 被 OnePlus 冻结，2 分钟后闹钟触发，此 Receiver 检测到心跳超时
 * 4. 立即通过 startForegroundService 重启 Service
 *
 * **与主看门狗的区别**：
 * - 主看门狗（StaticWidgetProvider）：负责刷新余额 + 更新 Widget，间隔 = 用户配置
 * - Keepalive（本 Receiver）：只检测 Service 存活，间隔 = 固定 2 分钟
 * - 两者互补：即使用户配 30 分钟刷新间隔，Service 冻结后最多 2 分钟就能恢复
 */
class KeepAliveReceiver : BroadcastReceiver() {

    /** 调度下一次 keepalive 闹钟（由 Service 在每次刷新后调用） */
    companion object {
        const val ACTION_KEEPALIVE = "com.example.deepseekbalance.KEEPALIVE_PING"
        private const val KEEPALIVE_INTERVAL_DEFAULT = 120_000L  // 2 分钟（标准）
        private const val KEEPALIVE_INTERVAL_OEM = 90_000L       // 90 秒（激进厂商）
        private const val REQUEST_CODE = 201

        /** OEM 激进后台限制厂商列表 */
        private val AGGRESSIVE_OEMS = setOf("oneplus", "oppo", "vivo", "xiaomi")

        /**
         * 设定 keepalive 闹钟。
         * - 普通设备：2 分钟间隔
         * - OnePlus/OPPO/vivo/Xiaomi：90 秒间隔（对抗激进冻结）
         * 使用同一 requestCode + FLAG_UPDATE_CURRENT，旧闹钟自动覆盖，不会堆积。
         */
        fun schedule(context: Context) {
            try {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(ACTION_KEEPALIVE).apply { setPackage(context.packageName) }
                val pending = PendingIntent.getBroadcast(
                    context, REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarm.cancel(pending)

                val isOem = Build.MANUFACTURER.lowercase() in AGGRESSIVE_OEMS
                val interval = if (isOem) KEEPALIVE_INTERVAL_OEM else KEEPALIVE_INTERVAL_DEFAULT
                val triggerTime = System.currentTimeMillis() + interval
                try {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, triggerTime, pending)
                } catch (_: SecurityException) {
                    alarm.set(AlarmManager.RTC, triggerTime, pending)
                }
            } catch (_: Exception) {
                // keepalive 失败不应影响主流程
            }
        }

        /** 取消 keepalive 闹钟（Service 停止时调用） */
        fun cancel(context: Context) {
            try {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(ACTION_KEEPALIVE).apply { setPackage(context.packageName) }
                val pending = PendingIntent.getBroadcast(
                    context, REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarm.cancel(pending)
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KEEPALIVE) return

        val now = System.currentTimeMillis()
        val svcDead = RefreshScheduler.isServiceDead(context, 90_000L) // 90 秒超时

        if (svcDead) {
            Logger.w("KeepAlive", "Service dead detected — restarting via foreground service")
            try {
                val svcIntent = Intent(context, BalanceRefreshService::class.java)
                context.startForegroundService(svcIntent)
                RefreshLogStore.addEntry(context, RefreshLogEntry(
                    id = now, type = RefreshLogType.WATCHDOG, timestamp = now,
                    message = "KeepAlive 检测到服务冻结，已通过前台服务重启"
                ))
            } catch (e: Exception) {
                Logger.e("KeepAlive", "Failed to restart service", e)
                RefreshLogStore.addEntry(context, RefreshLogEntry(
                    id = now, type = RefreshLogType.WATCHDOG, timestamp = now,
                    message = "KeepAlive 重启失败: ${e.message?.take(40)}"
                ))
            }
        }
        // Service 存活时无需操作 — 它会在下次刷新时重新设定 keepalive 闹钟
    }
}
