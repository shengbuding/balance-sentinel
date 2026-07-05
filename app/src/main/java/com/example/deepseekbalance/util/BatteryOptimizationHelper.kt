package com.example.deepseekbalance.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化引导工具。
 * - 首次启动弹引导 Dialog
 * - 提供跳转电池优化设置的 fallback 链路
 * - SharedPreferences 持久化标记，避免重复弹出
 */
object BatteryOptimizationHelper {

    private const val PREFS_NAME = "battery_guide"
    private const val KEY_GUIDE_SHOWN = "setup_guide_shown"
    private const val KEY_DISMISS_COUNT = "guide_dismiss_count"

    /** 是否应该显示引导（首次 + 电池优化未关闭 + 未超过最大弹窗次数） */
    fun shouldShowGuide(context: Context): Boolean {
        val prefs = getPrefs(context)
        val shown = prefs.getBoolean(KEY_GUIDE_SHOWN, false)
        if (shown) return false

        val dismissCount = prefs.getInt(KEY_DISMISS_COUNT, 0)
        if (dismissCount >= 3) return false // 最多弹 3 次

        return isBatteryOptimizing(context)
    }

    /** 标记引导已显示（用户同意后调用） */
    fun markGuideShown(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
    }

    /** 记录用户点了"稍后"（累计 3 次后不再弹） */
    fun recordDismiss(context: Context) {
        val prefs = getPrefs(context)
        val current = prefs.getInt(KEY_DISMISS_COUNT, 0)
        prefs.edit().putInt(KEY_DISMISS_COUNT, current + 1).apply()
    }

    /** 是否处于电池优化限制状态 */
    fun isBatteryOptimizing(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 打开电池优化设置页面。
     * 三级 fallback：直接请求白名单 → 电池优化列表 → 应用详情页。
     */
    fun openBatterySettings(context: Context): Boolean {
        val pkg = "package:${context.packageName}"
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse(pkg) },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse(pkg) }
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
