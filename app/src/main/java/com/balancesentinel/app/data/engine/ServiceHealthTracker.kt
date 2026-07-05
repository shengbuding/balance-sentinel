package com.balancesentinel.app.data.engine

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.util.Logger

/**
 * 刷新服务健康追踪器。
 *
 * 追踪连续失败次数，连续失败达阈值时发送通知或进入保护模式。
 * 所有数据仅存本地 SharedPreferences。
 */
object ServiceHealthTracker {

    private const val PREFS_NAME = "service_health"
    private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
    private const val KEY_PROTECTION_MODE = "protection_mode"

    private const val ALERT_THRESHOLD = 3      // 连续失败 >= 3 次 -> 发送通知
    private const val PROTECTION_THRESHOLD = 10 // 连续失败 >= 10 次 -> 保护模式

    /**
     * 记录一次成功的刷新。重置失败计数，退出保护模式。
     */
    fun recordSuccess(context: Context) {
        val p = getPrefs(context)
        val wasInProtection = p.getBoolean(KEY_PROTECTION_MODE, false)
        p.edit().apply {
            putInt(KEY_CONSECUTIVE_FAILURES, 0)
            putBoolean(KEY_PROTECTION_MODE, false)
        }.apply()
        if (wasInProtection) {
            Logger.i(TAG, "Exited protection mode — refresh succeeded")
        }
    }

    /**
     * 记录一次失败的刷新。递增失败计数，达到阈值时触发通知或保护模式。
     */
    fun recordFailure(context: Context) {
        val p = getPrefs(context)
        val current = p.getInt(KEY_CONSECUTIVE_FAILURES, 0)
        val next = current + 1
        val wasInProtection = p.getBoolean(KEY_PROTECTION_MODE, false)

        p.edit().putInt(KEY_CONSECUTIVE_FAILURES, next).apply()

        when {
            next >= PROTECTION_THRESHOLD && !wasInProtection -> {
                p.edit().putBoolean(KEY_PROTECTION_MODE, true).apply()
                Logger.w(TAG, "Entered protection mode after $next consecutive failures")
                try {
                    val nh = NotificationHelper(context)
                    nh.sendForegroundNotification(
                        "刷新服务异常",
                        "连续 $next 次失败，已降频到每小时一次。点击查看日志。"
                    )
                } catch (_: Exception) {}
            }
            next == ALERT_THRESHOLD -> {
                Logger.w(TAG, "$next consecutive refresh failures — sending alert")
                try {
                    val nh = NotificationHelper(context)
                    nh.sendForegroundNotification(
                        "刷新服务异常",
                        "连续 $next 次刷新失败，请检查网络或 API Key。"
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /** 是否处于保护模式（降频刷新）。 */
    fun isInProtectionMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROTECTION_MODE, false)
    }

    /** 当前连续失败次数。 */
    fun getConsecutiveFailures(context: Context): Int {
        return getPrefs(context).getInt(KEY_CONSECUTIVE_FAILURES, 0)
    }

    /** 重置所有状态（用于测试或手动清除）。 */
    fun reset(context: Context) {
        getPrefs(context).edit().apply {
            putInt(KEY_CONSECUTIVE_FAILURES, 0)
            putBoolean(KEY_PROTECTION_MODE, false)
        }.apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private const val TAG = "ServiceHealth"
}
