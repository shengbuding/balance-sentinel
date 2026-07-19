package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将 App 内部状态导出为可读文本文件。
 * 文件保存在 [Context.getExternalFilesDir] 或 [Context.filesDir]。
 */
object LogExporter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 导出完整调试报告到文件。
     * @return 导出文件的绝对路径，失败时返回 null
     */
    fun export(context: Context): String? {
        return try {
            val now = System.currentTimeMillis()
            val sb = StringBuilder()

            // 文件头
            sb.appendLine("══════════════════════════════════════════")
            sb.appendLine("  Wallet Sentinel — 调试报告")
            sb.appendLine("  导出时间: ${dateFmt.format(Date(now))}")
            sb.appendLine("══════════════════════════════════════════")
            sb.appendLine()

            // ── 系统状态 ──
            val status = RefreshScheduler.getStatusSummary(context)
            sb.appendLine("── 系统状态 ──")
            sb.appendLine("  前台服务   : ${if (status.serviceAlive) "存活" else "已停止"}")
            sb.appendLine("  电池优化   : ${if (status.batteryOptimizing) "限制中" else "已关闭"}")
            sb.appendLine("  闹钟方法   : ${status.alarmMethod}")
            sb.appendLine("  已设闹钟   : ${status.totalSet} 次")
            sb.appendLine("  已触发     : ${status.totalFired} 次")
            sb.appendLine("  被覆盖     : ${status.totalCancelled} 次")
            sb.appendLine("  被丢弃     : ${status.totalDropped} 次")
            if (status.totalSet > 0) {
                // 实际到达率：所有设定的闹钟中有多少真正触发了
                val arrivalRate = status.totalFired * 100 / status.totalSet
                sb.appendLine("  闹钟到达率 : ${arrivalRate}% (${status.totalFired}/${status.totalSet})")
                // 扣除覆盖后的有效率：排除主动覆盖的，看系统是否可靠投递
                val netSet = status.totalSet - status.totalCancelled
                if (netSet > 0 && netSet != status.totalSet) {
                    val effectiveRate = status.totalFired * 100 / netSet
                    sb.appendLine("  系统投递率 : ${effectiveRate}% (${status.totalFired}/${netSet}，扣除覆盖)")
                }
            }
            if (status.expectedNextRefresh > 0) {
                val delay = (now - status.expectedNextRefresh) / 1000
                sb.appendLine("  预定刷新   : ${dateFmt.format(Date(status.expectedNextRefresh))}")
                if (delay > 0) sb.appendLine("  已延迟     : ${delay / 60} 分 ${delay % 60} 秒")
            }
            if (status.lastHeartbeat > 0) {
                val ago = (now - status.lastHeartbeat) / 1000
                sb.appendLine("  最后心跳   : ${ago} 秒前")
            }
            sb.appendLine()

            // ── 设备信息 ──
            sb.appendLine("── 设备信息 ──")
            sb.appendLine("  制造商     : ${android.os.Build.MANUFACTURER}")
            sb.appendLine("  型号       : ${android.os.Build.MODEL}")
            sb.appendLine("  Android    : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            sb.appendLine()

            // ── 刷新日志 ──
            val entries = RefreshLogStore.getEntries(context)
            sb.appendLine("── 刷新日志 (${entries.size} 条) ──")
            entries.forEach { entry ->
                sb.appendLine(entry.toLogLine(dateFmt))
            }
            if (entries.isEmpty()) sb.appendLine("  (无记录)")
            sb.appendLine()

            // ── 崩溃日志 ──
            val crashes = CrashLogger.getCrashes(context.applicationContext as android.app.Application)
            sb.appendLine("── 崩溃日志 (${crashes.size} 条) ──")
            crashes.forEach { crash ->
                sb.appendLine("  ${crash.header}")
                sb.appendLine("  ${crash.fullStack}")
                sb.appendLine("  ---")
            }
            if (crashes.isEmpty()) sb.appendLine("  (无记录)")
            sb.appendLine()

            sb.appendLine("══════════════════════════════════════════")
            sb.appendLine("  报告结束")

            // 写入文件
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val fileName = "debug_report_${fileDateFmt.format(Date(now))}.txt"
            val file = File(dir, fileName)
            file.writeText(sb.toString())

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 格式化单条日志为可读行。
     */
    private fun RefreshLogEntry.toLogLine(fmt: SimpleDateFormat): String {
        val time = fmt.format(Date(timestamp))
        val typeStr = when (type) {
            RefreshLogType.MANUAL -> "[手动  ]"
            RefreshLogType.AUTO -> "[自动  ]"
            RefreshLogType.SCHEDULE -> "[调度  ]"
            RefreshLogType.MISSED -> "[遗漏! ]"
            RefreshLogType.SERVICE_DIED -> "[服务死]"
            RefreshLogType.SERVICE_START -> "[服务启]"
            RefreshLogType.WATCHDOG -> "[看门狗]"
        }

        val parts = mutableListOf<String>()
        parts.add("$typeStr $time")

        if (totalBalance.isNotEmpty()) {
            val sym = when (currency.uppercase()) { "CNY" -> "¥" "USD" -> "$" "EUR" -> "€" else -> currency }
            parts.add("$sym$totalBalance")
        }
        if (message.isNotEmpty()) parts.add(message)
        if (missReason.isNotEmpty()) parts.add("原因: $missReason")
        if (expectedTime > 0) parts.add("预定: ${fmt.format(Date(expectedTime))}")
        if (intervalSeconds > 0) {
            val intv = if (intervalSeconds < 60) "${intervalSeconds}秒" else "${intervalSeconds / 60}分钟"
            parts.add("间隔: $intv")
        }

        return "  " + parts.joinToString(" | ")
    }
}
