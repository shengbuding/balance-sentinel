package com.balancesentinel.app.util

import android.content.Context
import com.balancesentinel.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨模块共享的格式化工具函数。
 * 消除 HomeScreen / LogScreen / SettingsScreen / InsightsScreen /
 * BalanceRefreshService / StaticWidgetProvider / NotificationHelper
 * 中的重复 formatAmount / currencySymbol / formatFullTime / methodLabel 定义。
 */
object FormatUtils {

    // ═══════════════════════════════════════════════════════════
    // 纯函数（无 Android 依赖）
    // ═══════════════════════════════════════════════════════════

    /** 格式化余额为两位小数；解析失败返回原值 */
    fun formatAmount(amount: String): String {
        return try { "%.2f".format(amount.toDouble()) } catch (_: NumberFormatException) { amount }
    }

    /** 币种代码 → 符号 */
    fun currencySymbol(currency: String): String = when (currency.uppercase()) {
        "CNY" -> "¥"
        "USD" -> "$"
        "EUR" -> "€"
        else -> currency
    }

    /** 秒数 → 人类可读的中文时长 */
    fun formatInterval(seconds: Int): String = when {
        seconds < 60 -> "${seconds}秒"
        seconds % 60 == 0 -> "${seconds / 60}分钟"
        else -> "${seconds / 60}分${seconds % 60}秒"
    }

    // ═══════════════════════════════════════════════════════════
    // Context 相关函数
    // ═══════════════════════════════════════════════════════════

    /** 时间戳 → MM-dd HH:mm */
    fun formatFullTime(timestamp: Long): String {
        return try {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            fmt.format(Date(timestamp))
        } catch (_: Exception) { timestamp.toString() }
    }

    /** 闹钟调度方式 → 人类可读标签 */
    fun methodLabel(context: Context, method: String): String = when (method) {
        "alarm_clock" -> context.getString(R.string.alarm_method_alarm_clock)
        "exact" -> context.getString(R.string.alarm_method_exact)
        "inexact" -> context.getString(R.string.alarm_method_inexact)
        "failed" -> context.getString(R.string.alarm_method_failed)
        "foreground_service" -> context.getString(R.string.alarm_method_service_loop)
        else -> method.ifEmpty { context.getString(R.string.alarm_method_none) }
    }
}
