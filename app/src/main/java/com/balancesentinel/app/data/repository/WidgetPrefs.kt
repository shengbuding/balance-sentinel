package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable

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

    // ── Per-account+currency 启用开关（v2.1 新增）──

    /**
     * 检查指定账户+币种的余额预警是否启用。
     * 如果尚未设置 per-account+currency 值，回退到旧版全局 [alertEnabled]。
     */
    fun isBalanceAlertEnabled(accountId: String, currency: String): Boolean {
        val key = "${KEY_ALERT_ENABLED}_${accountId}_$currency"
        return if (prefs.contains(key)) prefs.getBoolean(key, false)
        else alertEnabled
    }

    fun setBalanceAlertEnabled(accountId: String, currency: String, enabled: Boolean) {
        prefs.edit().putBoolean("${KEY_ALERT_ENABLED}_${accountId}_$currency", enabled).apply()
    }

    /**
     * 检查指定账户+币种的异动提醒是否启用。
     * 如果尚未设置 per-account+currency 值，回退到旧版全局 [changeAlertEnabled]。
     */
    fun isChangeAlertEnabled(accountId: String, currency: String): Boolean {
        val key = "${KEY_CHANGE_ALERT_ENABLED}_${accountId}_$currency"
        return if (prefs.contains(key)) prefs.getBoolean(key, false)
        else changeAlertEnabled
    }

    fun setChangeAlertEnabled(accountId: String, currency: String, enabled: Boolean) {
        prefs.edit().putBoolean("${KEY_CHANGE_ALERT_ENABLED}_${accountId}_$currency", enabled).apply()
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

    /** 删除指定账户+币种的所有预警状态（含 per-currency 启用开关） */
    fun removeAccountCurrencyAlertState(accountId: String, currency: String) {
        prefs.edit().apply {
            remove("${KEY_ALERT_ENABLED}_${accountId}_$currency")
            remove("${KEY_CHANGE_ALERT_ENABLED}_${accountId}_$currency")
        }.apply()
    }

    // ── Per-account+currency 设置批量导出/导入 ──

    /**
     * 扫描所有 per-account+currency 预警启用开关，用于配置导出。
     * 返回扁平列表，每个条目代表一个 account+currency 组合的独立设置。
     */
    fun getAllPerCurrencyAlertSettings(): List<PerCurrencyAlertSetting> {
        val result = mutableListOf<PerCurrencyAlertSetting>()
        for (key in prefs.all.keys) {
            when {
                key.startsWith("${KEY_ALERT_ENABLED}_") -> {
                    val suffix = key.removePrefix("${KEY_ALERT_ENABLED}_")
                    val parts = suffix.split("_", limit = 2)
                    if (parts.size == 2) {
                        val (accountId, currency) = parts
                        val balanceOn = prefs.getBoolean(key, false)
                        val changeKey = "${KEY_CHANGE_ALERT_ENABLED}_${accountId}_$currency"
                        val changeOn = prefs.getBoolean(changeKey, false)
                        result.add(PerCurrencyAlertSetting(accountId, currency, balanceOn, changeOn))
                    }
                }
            }
        }
        return result.distinctBy { "${it.accountId}_${it.currency}" }
    }

    /** 批量导入 per-account+currency 预警启用开关（覆盖同 key 旧值）。 */
    fun applyPerCurrencyAlertSettings(settings: List<PerCurrencyAlertSetting>) {
        val editor = prefs.edit()
        for (s in settings) {
            editor.putBoolean("${KEY_ALERT_ENABLED}_${s.accountId}_${s.currency}", s.balanceAlertEnabled)
            editor.putBoolean("${KEY_CHANGE_ALERT_ENABLED}_${s.accountId}_${s.currency}", s.changeAlertEnabled)
        }
        editor.apply()
    }

    // ── 通知栏显示偏好（v2.5）──

    /** 是否在通知栏显示总余额。默认 true。设置时自动同步排序列表。 */
    var showTotalBalanceInNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_SHOW_TOTAL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_SHOW_TOTAL, value).apply()
            // 同步排序列表中的总余额条目
            val order = getRawNotificationWalletOrder().toMutableList()
            if (value && KEY_NOTIFICATION_TOTAL !in order) {
                order.add(0, KEY_NOTIFICATION_TOTAL)
                setNotificationWalletOrder(order)
            } else if (!value) {
                order.remove(KEY_NOTIFICATION_TOTAL)
                setNotificationWalletOrder(order)
            }
        }

    /**
     * 获取通知栏展示顺序列表（v2.5 含总余额条目）。
     * 包含 "__total__" 条目（如果启用）+ "accountId_currency" 条目。
     * 首次调用时自动从旧版布尔 key 迁移。
     */
    fun getNotificationWalletOrder(): List<String> {
        val order = getRawNotificationWalletOrder().toMutableList()
        // 确保与 showTotal 标记一致
        if (showTotalBalanceInNotification && KEY_NOTIFICATION_TOTAL !in order) {
            order.add(0, KEY_NOTIFICATION_TOTAL)
            setNotificationWalletOrder(order)
        } else if (!showTotalBalanceInNotification) {
            order.remove(KEY_NOTIFICATION_TOTAL)
        }
        return order
    }

    /** 读取原始排序列表（不做 total 一致性修正）。 */
    private fun getRawNotificationWalletOrder(): List<String> {
        val raw = prefs.getString(KEY_NOTIFICATION_WALLET_ORDER, null)
        if (raw != null) {
            return try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<String>>(raw)
            } catch (_: Exception) { emptyList() }
        }
        // 从旧版布尔 key 迁移
        val migrated = mutableListOf<String>()
        for (key in prefs.all.keys) {
            if (key.startsWith("${KEY_NOTIFICATION_SELECTED}_")) {
                val suffix = key.removePrefix("${KEY_NOTIFICATION_SELECTED}_")
                if (prefs.getBoolean(key, false)) {
                    migrated.add(suffix)
                }
            }
        }
        if (migrated.isNotEmpty()) {
            setNotificationWalletOrder(migrated)
        }
        return migrated
    }

    private fun setNotificationWalletOrder(order: List<String>) {
        // 手动构造 JSON 数组，避免额外的 kotlinx.serialization 依赖
        val raw = order.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        prefs.edit().putString(KEY_NOTIFICATION_WALLET_ORDER, raw).apply()
    }

    /** 总余额是否在排序列表中。 */
    fun isTotalInNotification(): Boolean = showTotalBalanceInNotification

    /** 检查指定账户+币种是否在通知栏展示列表中。 */
    fun isNotificationWalletSelected(accountId: String, currency: String): Boolean {
        return "${accountId}_$currency" in getNotificationWalletOrder()
    }

    /** 设置指定账户+币种的通知栏展示。true=加入末尾，false=移除。 */
    fun setNotificationWalletSelected(accountId: String, currency: String, selected: Boolean) {
        val key = "${accountId}_$currency"
        val order = getRawNotificationWalletOrder().toMutableList()
        if (selected && key !in order) {
            order.add(key)
        } else if (!selected) {
            order.remove(key)
        }
        setNotificationWalletOrder(order)
        // 同步旧版布尔 key 保持兼容
        prefs.edit().putBoolean("${KEY_NOTIFICATION_SELECTED}_${accountId}_$currency", selected).apply()
    }

    /** 将指定条目在排序中上移一位（accountId=TOTAL_KEY 表示总余额）。 */
    fun moveNotificationWalletUp(accountId: String, currency: String) {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) KEY_NOTIFICATION_TOTAL else "${accountId}_$currency"
        moveEntryUp(key)
    }

    /** 将指定条目在排序中下移一位。 */
    fun moveNotificationWalletDown(accountId: String, currency: String) {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) KEY_NOTIFICATION_TOTAL else "${accountId}_$currency"
        moveEntryDown(key)
    }

    private fun moveEntryUp(key: String) {
        val order = getNotificationWalletOrder().toMutableList()
        val idx = order.indexOf(key)
        if (idx > 0) {
            order.removeAt(idx)
            order.add(idx - 1, key)
            setNotificationWalletOrder(order)
        }
    }

    private fun moveEntryDown(key: String) {
        val order = getNotificationWalletOrder().toMutableList()
        val idx = order.indexOf(key)
        if (idx >= 0 && idx < order.size - 1) {
            order.removeAt(idx)
            order.add(idx + 1, key)
            setNotificationWalletOrder(order)
        }
    }

    /** 获取指定条目在排序中的位置（0-based），未选中返回 -1。accountId=TOTAL_KEY 查总余额。 */
    fun getNotificationWalletPosition(accountId: String, currency: String): Int {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) KEY_NOTIFICATION_TOTAL else "${accountId}_$currency"
        return getNotificationWalletOrder().indexOf(key)
    }

    /** 获取通知栏排序列表的总长度（含总余额条目）。 */
    fun getNotificationWalletCount(): Int = getNotificationWalletOrder().size

    /** 批量获取所有通知栏钱包选择（用于配置导出）。 */
    fun getAllNotificationWalletSelections(): List<NotificationWalletSelection> {
        return getNotificationWalletOrder().map { key ->
            val parts = key.split("_", limit = 2)
            NotificationWalletSelection(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
        }
    }

    /** 批量导入通知栏钱包选择（覆盖排序列表）。 */
    fun applyNotificationWalletSelections(selections: List<NotificationWalletSelection>) {
        val order = selections.map { "${it.accountId}_${it.currency}" }
        setNotificationWalletOrder(order)
        // 同步旧版布尔 key
        val editor = prefs.edit()
        for (s in selections) {
            editor.putBoolean("${KEY_NOTIFICATION_SELECTED}_${s.accountId}_${s.currency}", true)
        }
        editor.apply()
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
        const val KEY_NOTIFICATION_SHOW_TOTAL = "notification_show_total"
        const val KEY_NOTIFICATION_SELECTED = "notification_selected"
        const val KEY_NOTIFICATION_WALLET_ORDER = "notification_wallet_order"
        const val KEY_NOTIFICATION_TOTAL = "__total__"
    }
}

/** Snooze 状态快照。由 [WidgetPrefs.getSnoozeInfo] 返回。 */
data class SnoozeInfo(
    val anySnoozed: Boolean = false,
    val maxRemainingMs: Long = 0L,
    val snoozedAccountIds: List<String> = emptyList()
)

/** Per-account+currency 预警启用设置。用于配置导出/导入。 */
@Serializable
data class PerCurrencyAlertSetting(
    val accountId: String,
    val currency: String,
    val balanceAlertEnabled: Boolean,
    val changeAlertEnabled: Boolean
)

/** 通知栏钱包选择。用于配置导出/导入。 */
@Serializable
data class NotificationWalletSelection(
    val accountId: String,
    val currency: String
)
