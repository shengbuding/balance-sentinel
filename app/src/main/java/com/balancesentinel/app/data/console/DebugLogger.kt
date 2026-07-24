package com.balancesentinel.app.data.console

import com.balancesentinel.app.BuildConfig

/**
 * 全局调试日志收集器
 * 仅在 Debug 构建中记录日志，Release 构建中完全禁用
 */
object DebugLogger {
    private val logs = mutableListOf<String>()
    private const val MAX_LOGS = 100

    fun log(message: String) {
        // Release 构建中完全禁用日志记录
        if (!BuildConfig.DEBUG) return

        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(logs) {
            logs.add("[$timestamp] $message")
            while (logs.size > MAX_LOGS) {
                logs.removeFirst()
            }
        }
    }

    fun getLogs(): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        synchronized(logs) {
            return logs.toList()
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
