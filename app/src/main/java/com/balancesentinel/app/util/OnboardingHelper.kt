package com.balancesentinel.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 首次启动引导工具。
 * - 首次安装后显示引导页
 * - SharedPreferences 持久化标记，避免重复显示
 * - 提供重置方法用于测试和设置页
 */
object OnboardingHelper {

    private const val PREFS_NAME = "onboarding"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_VERSION = "onboarding_version"

    /** 当前引导版本号 —— bump 此值可强制所有用户重新看到引导 */
    private const val CURRENT_VERSION = 1

    /** 是否应该显示引导页 */
    fun shouldShow(context: Context): Boolean {
        val prefs = getPrefs(context)
        val completed = prefs.getBoolean(KEY_COMPLETED, false)
        if (completed) {
            // 检查版本号：如果引导内容更新了，即使 completed=true 也重新显示
            val savedVersion = prefs.getInt(KEY_VERSION, 0)
            if (savedVersion >= CURRENT_VERSION) return false
        }
        return true
    }

    /** 标记引导已完成 */
    fun markCompleted(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_COMPLETED, true)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
    }

    /** 重置引导状态（供设置页或测试使用） */
    fun reset(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
