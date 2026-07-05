package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 小组件偏好设置（多账户版）。
 * 全局设置（刷新间隔、日志上限、预警阈值）不变。
 * 预警/异动的去重状态（lastAlertedBalance、previousBalance 等）按 accountId 隔离。
 */
class WidgetPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

    // ── 全局设置 ──

    var refreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var logMaxEntries: Int
        get() = prefs.getInt(KEY_LOG_MAX, DEFAULT_LOG_MAX)
        set(value) = prefs.edit().putInt(KEY_LOG_MAX, value.coerceIn(10, 1000)).apply()

    var alertEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_ENABLED, value).apply()

    var alertThreshold: Float
        get() = prefs.getFloat(KEY_ALERT_THRESHOLD, 0f)
        set(value) = prefs.edit().putFloat(KEY_ALERT_THRESHOLD, value).apply()

    var changeAlertEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHANGE_ALERT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CHANGE_ALERT_ENABLED, value).apply()

    var changeAlertThreshold: Float
        get() = prefs.getFloat(KEY_CHANGE_ALERT_THRESHOLD, 0f)
        set(value) = prefs.edit().putFloat(KEY_CHANGE_ALERT_THRESHOLD, value).apply()

    var changeAlertPeriodMinutes: Int
        get() = prefs.getInt(KEY_CHANGE_ALERT_PERIOD, 0)
        set(value) = prefs.edit().putInt(KEY_CHANGE_ALERT_PERIOD, value).apply()

    /** 用户自定义的暂停预警时长（分钟），默认 60 分钟 */
    var snoozeDurationMinutes: Int
        get() = prefs.getInt(KEY_SNOOZE_DURATION_MINUTES, DEFAULT_SNOOZE_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SNOOZE_DURATION_MINUTES, value.coerceIn(5, 1440)).apply()

    // ── 按账户隔离的去重状态 ──

    fun getLastAlertedBalance(accountId: String): Float =
        prefs.getFloat("${KEY_LAST_ALERTED_BALANCE}_$accountId", -1f)

    fun setLastAlertedBalance(accountId: String, value: Float) =
        prefs.edit().putFloat("${KEY_LAST_ALERTED_BALANCE}_$accountId", value).apply()

    fun getPreviousBalance(accountId: String): Float =
        prefs.getFloat("${KEY_PREVIOUS_BALANCE}_$accountId", -1f)

    fun setPreviousBalance(accountId: String, value: Float) =
        prefs.edit().putFloat("${KEY_PREVIOUS_BALANCE}_$accountId", value).apply()

    fun getPreviousBalanceTime(accountId: String): Long =
        prefs.getLong("${KEY_PREVIOUS_BALANCE_TIME}_$accountId", 0L)

    fun setPreviousBalanceTime(accountId: String, value: Long) =
        prefs.edit().putLong("${KEY_PREVIOUS_BALANCE_TIME}_$accountId", value).apply()

    fun getLastChangeAlertedBalance(accountId: String): Float =
        prefs.getFloat("${KEY_LAST_CHANGE_ALERTED_BALANCE}_$accountId", -1f)

    fun setLastChangeAlertedBalance(accountId: String, value: Float) =
        prefs.edit().putFloat("${KEY_LAST_CHANGE_ALERTED_BALANCE}_$accountId", value).apply()

    fun getLastChangeAlertedTime(accountId: String): Long =
        prefs.getLong("${KEY_LAST_CHANGE_ALERTED_TIME}_$accountId", 0L)

    fun setLastChangeAlertedTime(accountId: String, value: Long) =
        prefs.edit().putLong("${KEY_LAST_CHANGE_ALERTED_TIME}_$accountId", value).apply()

    // ── Snooze 标记（按账户隔离）──

    /** 获取该账户的 snooze 截止时间戳。0 表示未 snooze。 */
    fun getSnoozeUntil(accountId: String): Long =
        prefs.getLong("${KEY_SNOOZE_UNTIL}_$accountId", 0L)

    /** 设置该账户的 snooze 截止时间戳。传 0 清除。 */
    fun setSnoozeUntil(accountId: String, until: Long) =
        prefs.edit().putLong("${KEY_SNOOZE_UNTIL}_$accountId", until).apply()

    /** 清除所有账户的 snooze 标记 */
    fun clearAllSnooze() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_SNOOZE_UNTIL) }.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }

    /** 获取所有账户的 snooze 信息。返回 active snooze 中最早到期的截止时间戳，以及 snoozed 的账户 ID 列表 */
    fun getSnoozeInfo(): SnoozeInfo {
        val now = System.currentTimeMillis()
        val snoozedAccounts = mutableListOf<String>()
        var maxRemainingMs = 0L
        prefs.all.keys.filter { it.startsWith(KEY_SNOOZE_UNTIL) }.forEach { key ->
            val until = prefs.getLong(key, 0L)
            if (until > now) {
                val accountId = key.removePrefix("${KEY_SNOOZE_UNTIL}_")
                snoozedAccounts.add(accountId)
                if (until - now > maxRemainingMs) {
                    maxRemainingMs = until - now
                }
            } else if (until > 0L) {
                // 清理已过期的 snooze
                prefs.edit().remove(key).apply()
            }
        }
        return SnoozeInfo(
            anySnoozed = snoozedAccounts.isNotEmpty(),
            maxRemainingMs = maxRemainingMs,
            snoozedAccountIds = snoozedAccounts
        )
    }

    /** 将所有设置恢复为默认值（清空整个 widget_prefs）。 */
    fun resetAll() {
        prefs.edit().clear().apply()
    }

    // ── 清理 ──

    /** 删除指定账户的所有预警状态 */
    fun removeAccountAlertState(accountId: String) {
        prefs.edit().apply {
            remove("${KEY_LAST_ALERTED_BALANCE}_$accountId")
            remove("${KEY_PREVIOUS_BALANCE}_$accountId")
            remove("${KEY_PREVIOUS_BALANCE_TIME}_$accountId")
            remove("${KEY_LAST_CHANGE_ALERTED_BALANCE}_$accountId")
            remove("${KEY_LAST_CHANGE_ALERTED_TIME}_$accountId")
        }.apply()
    }

    companion object {
        const val KEY_INTERVAL = "refresh_interval_seconds"
        const val DEFAULT_INTERVAL = 30     // 30 seconds
        const val KEY_LOG_MAX = "log_max_entries"
        const val DEFAULT_LOG_MAX = 100
        const val KEY_ALERT_ENABLED = "alert_enabled"
        const val KEY_ALERT_THRESHOLD = "alert_threshold"
        const val KEY_LAST_ALERTED_BALANCE = "last_alerted_balance"
        const val KEY_CHANGE_ALERT_ENABLED = "change_alert_enabled"
        const val KEY_CHANGE_ALERT_THRESHOLD = "change_alert_threshold"
        const val KEY_CHANGE_ALERT_PERIOD = "change_alert_period"
        const val KEY_PREVIOUS_BALANCE = "previous_balance"
        const val KEY_PREVIOUS_BALANCE_TIME = "previous_balance_time"
        const val KEY_LAST_CHANGE_ALERTED_BALANCE = "last_change_alerted_balance"
        const val KEY_LAST_CHANGE_ALERTED_TIME = "last_change_alerted_time"
        const val KEY_SNOOZE_UNTIL = "snooze_until"
        const val KEY_SNOOZE_DURATION_MINUTES = "snooze_duration_minutes"
        const val DEFAULT_SNOOZE_MINUTES = 60
    }
}

/** Snooze 状态快照。由 [WidgetPrefs.getSnoozeInfo] 返回。 */
data class SnoozeInfo(
    val anySnoozed: Boolean = false,
    val maxRemainingMs: Long = 0L,
    val snoozedAccountIds: List<String> = emptyList()
)
