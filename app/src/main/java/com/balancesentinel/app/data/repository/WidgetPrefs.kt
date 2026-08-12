package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.Serializable
import java.util.Locale

data class AlertIdentity(val accountId: String, val currency: String) {
    val normalizedCurrency: String = currency.uppercase(Locale.ROOT)
    val storageSuffix: String = "${accountId}_${normalizedCurrency}"
}

/**
 * 小组件偏好设置（多账户版）。
 * 全局设置（刷新间隔、日志上限、预警阈值）不变。
 * 预警/异动的去重状态按 accountId + currency 隔离。
 */
class WidgetPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

    init {
        migrateLegacyAlertState()
    }

    private fun commitAccountState(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(WIDGET_PREFS_LOCK) {
            val editor = prefs.edit()
            editor.block()
            check(editor.commit())
        }
    }

    private fun pairKey(prefix: String, accountId: String, currency: String): String =
        "${prefix}_${AlertIdentity(accountId, currency).storageSuffix}"

    private fun migrateLegacyAlertState() {
        synchronized(WIDGET_PREFS_LOCK) {
            if (prefs.getBoolean(KEY_ALERT_PAIR_STATE_MIGRATED, false)) return

            val editor = prefs.edit()
            for (key in prefs.all.keys) {
                val legacyPrefix = LEGACY_ALERT_STATE_PREFIXES.firstOrNull { prefix ->
                    key.startsWith("${prefix}_")
                }
                if (legacyPrefix != null) {
                    val suffix = key.removePrefix("${legacyPrefix}_")
                    if ('_' !in suffix) editor.remove(key)
                }

                for (prefix in PAIR_ENABLE_PREFIXES) {
                    if (!key.startsWith("${prefix}_")) continue
                    val suffix = key.removePrefix("${prefix}_")
                    val parts = suffix.split("_", limit = 2)
                    if (parts.size != 2) continue
                    val normalizedKey = pairKey(prefix, parts[0], parts[1])
                    if (normalizedKey != key) {
                        editor.putBoolean(normalizedKey, prefs.getBoolean(key, false))
                        editor.remove(key)
                    }
                }
            }
            editor.putBoolean(KEY_ALERT_PAIR_STATE_MIGRATED, true)
            check(editor.commit())
        }
    }

    // ── 全局设置 ──

    var refreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var logMaxEntries: Int
        get() = prefs.getInt(KEY_LOG_MAX, DEFAULT_LOG_MAX)
        set(value) = prefs.edit().putInt(KEY_LOG_MAX, value.coerceIn(10, 1000)).apply()

    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, value).apply()

    var notificationPermissionPermanentlyDenied: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PERMANENTLY_DENIED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_PERMANENTLY_DENIED, value).apply()

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

    /** 用户选择的语言偏好，null = 未设置 = 跟随系统 */
    var language: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) {
            if (value != null) {
                prefs.edit().putString(KEY_LANGUAGE, value).apply()
            } else {
                prefs.edit().remove(KEY_LANGUAGE).apply()
            }
        }

    // ── 按账户+币种隔离的去重状态 ──

    fun getLastAlertedBalance(accountId: String, currency: String): Float =
        prefs.getFloat(pairKey(KEY_LAST_ALERTED_BALANCE, accountId, currency), -1f)

    fun setLastAlertedBalance(accountId: String, currency: String, value: Float) {
        commitAccountState {
            putFloat(pairKey(KEY_LAST_ALERTED_BALANCE, accountId, currency), value)
        }
    }

    fun getPreviousBalance(accountId: String, currency: String): Float =
        prefs.getFloat(pairKey(KEY_PREVIOUS_BALANCE, accountId, currency), -1f)

    fun setPreviousBalance(accountId: String, currency: String, value: Float) {
        commitAccountState {
            putFloat(pairKey(KEY_PREVIOUS_BALANCE, accountId, currency), value)
        }
    }

    fun getPreviousBalanceTime(accountId: String, currency: String): Long =
        prefs.getLong(pairKey(KEY_PREVIOUS_BALANCE_TIME, accountId, currency), 0L)

    fun setPreviousBalanceTime(accountId: String, currency: String, value: Long) {
        commitAccountState {
            putLong(pairKey(KEY_PREVIOUS_BALANCE_TIME, accountId, currency), value)
        }
    }

    fun getLastChangeAlertedBalance(accountId: String, currency: String): Float =
        prefs.getFloat(pairKey(KEY_LAST_CHANGE_ALERTED_BALANCE, accountId, currency), -1f)

    fun setLastChangeAlertedBalance(accountId: String, currency: String, value: Float) {
        commitAccountState {
            putFloat(pairKey(KEY_LAST_CHANGE_ALERTED_BALANCE, accountId, currency), value)
        }
    }

    fun getLastChangeAlertedTime(accountId: String, currency: String): Long =
        prefs.getLong(pairKey(KEY_LAST_CHANGE_ALERTED_TIME, accountId, currency), 0L)

    fun setLastChangeAlertedTime(accountId: String, currency: String, value: Long) {
        commitAccountState {
            putLong(pairKey(KEY_LAST_CHANGE_ALERTED_TIME, accountId, currency), value)
        }
    }

    // ── Snooze 标记（按账户隔离）──

    /** 获取该账户的 snooze 截止时间戳。0 表示未 snooze。 */
    fun getSnoozeUntil(accountId: String): Long =
        prefs.getLong("${KEY_SNOOZE_UNTIL}_$accountId", 0L)

    /** 设置该账户的 snooze 截止时间戳。传 0 清除。 */
    fun setSnoozeUntil(accountId: String, until: Long) {
        commitAccountState { putLong("${KEY_SNOOZE_UNTIL}_$accountId", until) }
    }

    /** 清除所有账户的 snooze 标记 */
    fun clearAllSnooze() {
        commitAccountState {
            prefs.all.keys.filter { it.startsWith(KEY_SNOOZE_UNTIL) }.forEach(::remove)
        }
    }

    /** 获取所有账户的 snooze 信息。返回 active snooze 中最早到期的截止时间戳，以及 snoozed 的账户 ID 列表 */
    fun getSnoozeInfo(): SnoozeInfo {
        return synchronized(WIDGET_PREFS_LOCK) {
            val now = System.currentTimeMillis()
            val snoozedAccounts = mutableListOf<String>()
            val expiredKeys = mutableListOf<String>()
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
                    expiredKeys.add(key)
                }
            }
            if (expiredKeys.isNotEmpty()) {
                val editor = prefs.edit()
                expiredKeys.forEach(editor::remove)
                check(editor.commit())
            }
            SnoozeInfo(
                anySnoozed = snoozedAccounts.isNotEmpty(),
                maxRemainingMs = maxRemainingMs,
                snoozedAccountIds = snoozedAccounts
            )
        }
    }

    /** 将所有设置恢复为默认值（清空整个 widget_prefs）。 */
    fun resetAll() {
        synchronized(WIDGET_PREFS_LOCK) {
            check(prefs.edit().clear().commit())
        }
    }

    // ── Per-account+currency 启用开关（v2.1 新增）──

    /**
     * 检查指定账户+币种的余额预警是否启用。
     * 如果尚未设置 per-account+currency 值，回退到旧版全局 [alertEnabled]。
     */
    fun isBalanceAlertEnabled(accountId: String, currency: String): Boolean {
        val key = pairKey(KEY_ALERT_ENABLED, accountId, currency)
        return if (prefs.contains(key)) prefs.getBoolean(key, false)
        else alertEnabled
    }

    fun setBalanceAlertEnabled(accountId: String, currency: String, enabled: Boolean) {
        commitAccountState {
            putBoolean(pairKey(KEY_ALERT_ENABLED, accountId, currency), enabled)
        }
    }

    /**
     * 检查指定账户+币种的异动提醒是否启用。
     * 如果尚未设置 per-account+currency 值，回退到旧版全局 [changeAlertEnabled]。
     */
    fun isChangeAlertEnabled(accountId: String, currency: String): Boolean {
        val key = pairKey(KEY_CHANGE_ALERT_ENABLED, accountId, currency)
        return if (prefs.contains(key)) prefs.getBoolean(key, false)
        else changeAlertEnabled
    }

    fun setChangeAlertEnabled(accountId: String, currency: String, enabled: Boolean) {
        commitAccountState {
            putBoolean(pairKey(KEY_CHANGE_ALERT_ENABLED, accountId, currency), enabled)
        }
    }

    // ── 清理 ──

    /** 删除指定账户的所有预警状态 */
    fun removeAccountAlertState(accountId: String) {
        synchronized(WIDGET_PREFS_LOCK) {
            val order = getRawNotificationWalletOrder()
                .filterNot { it.startsWith("${accountId}_") }
            val editor = prefs.edit()
            prefs.all.keys
                .filter { key ->
                    PAIR_ALERT_PREFIXES.any { prefix ->
                        key.startsWith("${prefix}_${accountId}_")
                    } || LEGACY_ALERT_STATE_PREFIXES.any { prefix ->
                        key == "${prefix}_$accountId"
                    }
                }
                .forEach(editor::remove)
            editor.putNotificationWalletOrder(order)
            check(editor.commit())
        }
    }

    /** 删除指定账户+币种的所有预警状态（含 per-currency 启用开关） */
    fun removeAccountCurrencyAlertState(accountId: String, currency: String) {
        synchronized(WIDGET_PREFS_LOCK) {
            val identity = AlertIdentity(accountId, currency)
            val order = getRawNotificationWalletOrder()
                .filterNot { it == identity.storageSuffix }
            val editor = prefs.edit()
            PAIR_ALERT_PREFIXES.forEach { prefix ->
                editor.remove("${prefix}_${identity.storageSuffix}")
            }
            editor.putNotificationWalletOrder(order)
            check(editor.commit())
        }
    }

    fun removeAccountData(accountId: String) {
        synchronized(WIDGET_PREFS_LOCK) {
            val order = getRawNotificationWalletOrder()
                .filterNot { it.startsWith("${accountId}_") }
            val editor = prefs.edit()
            prefs.all.keys
                .filter { key -> key.endsWith("_$accountId") || key.contains("_${accountId}_") }
                .forEach(editor::remove)
            editor.putNotificationWalletOrder(order)
            check(editor.commit())
        }
    }

    fun migrateAccountData(oldAccountId: String, newAccountId: String) {
        if (oldAccountId == newAccountId) return

        synchronized(WIDGET_PREFS_LOCK) {
            val order = getRawNotificationWalletOrder().map { entry ->
                if (entry.startsWith("${oldAccountId}_")) {
                    "${newAccountId}_${entry.removePrefix("${oldAccountId}_")}"
                } else {
                    entry
                }
            }
            val editor = prefs.edit()
            prefs.all.forEach { (key, value) ->
                when {
                    PAIR_ENABLE_PREFIXES.any { prefix ->
                        key.startsWith("${prefix}_${oldAccountId}_")
                    } -> {
                        val prefix = PAIR_ENABLE_PREFIXES.first { candidate ->
                            key.startsWith("${candidate}_${oldAccountId}_")
                        }
                        val currency = key.removePrefix("${prefix}_${oldAccountId}_")
                        if (value is Boolean) {
                            editor.putBoolean(pairKey(prefix, newAccountId, currency), value)
                        }
                        editor.remove(key)
                    }

                    PAIR_STATE_PREFIXES.any { prefix ->
                        key.startsWith("${prefix}_${oldAccountId}_") ||
                            key.startsWith("${prefix}_${newAccountId}_")
                    } -> editor.remove(key)

                    key.startsWith("${KEY_NOTIFICATION_SELECTED}_${oldAccountId}_") -> {
                        val currency = key.removePrefix("${KEY_NOTIFICATION_SELECTED}_${oldAccountId}_")
                        if (value is Boolean) {
                            editor.putBoolean(
                                pairKey(KEY_NOTIFICATION_SELECTED, newAccountId, currency),
                                value
                            )
                        }
                        editor.remove(key)
                    }

                    key == "${KEY_SNOOZE_UNTIL}_$oldAccountId" -> {
                        if (value is Long) {
                            editor.putLong("${KEY_SNOOZE_UNTIL}_$newAccountId", value)
                        }
                        editor.remove(key)
                    }

                    LEGACY_ALERT_STATE_PREFIXES.any { prefix ->
                        key == "${prefix}_$oldAccountId" || key == "${prefix}_$newAccountId"
                    } -> editor.remove(key)
                }
            }
            editor.putNotificationWalletOrder(order)
            check(editor.commit())
        }
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
                        val normalizedCurrency = AlertIdentity(accountId, currency).normalizedCurrency
                        val changeKey = pairKey(KEY_CHANGE_ALERT_ENABLED, accountId, normalizedCurrency)
                        val changeOn = prefs.getBoolean(changeKey, false)
                        result.add(
                            PerCurrencyAlertSetting(
                                accountId,
                                normalizedCurrency,
                                balanceOn,
                                changeOn
                            )
                        )
                    }
                }
            }
        }
        return result.distinctBy { "${it.accountId}_${it.currency}" }
    }

    /** 批量导入 per-account+currency 预警启用开关（覆盖同 key 旧值）。 */
    fun applyPerCurrencyAlertSettings(settings: List<PerCurrencyAlertSetting>) {
        commitAccountState {
            for (setting in settings) {
                putBoolean(
                    pairKey(KEY_ALERT_ENABLED, setting.accountId, setting.currency),
                    setting.balanceAlertEnabled
                )
                putBoolean(
                    pairKey(KEY_CHANGE_ALERT_ENABLED, setting.accountId, setting.currency),
                    setting.changeAlertEnabled
                )
            }
        }
    }

    // ── 通知栏显示偏好（v2.5）──

    /** 是否在通知栏显示总余额。默认 true。设置时自动同步排序列表。 */
    var showTotalBalanceInNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_SHOW_TOTAL, true)
        set(value) {
            synchronized(WIDGET_PREFS_LOCK) {
                val order = getRawNotificationWalletOrder().toMutableList()
                if (value && KEY_NOTIFICATION_TOTAL !in order) {
                    order.add(0, KEY_NOTIFICATION_TOTAL)
                } else if (!value) {
                    order.remove(KEY_NOTIFICATION_TOTAL)
                }
                check(
                    prefs.edit()
                        .putBoolean(KEY_NOTIFICATION_SHOW_TOTAL, value)
                        .putNotificationWalletOrder(order)
                        .commit()
                )
            }
        }

    /**
     * 获取通知栏展示顺序列表（v2.5 含总余额条目）。
     * 包含 "__total__" 条目（如果启用）+ "accountId_currency" 条目。
     * 首次调用时自动从旧版布尔 key 迁移。
     */
    fun getNotificationWalletOrder(): List<String> {
        return synchronized(WIDGET_PREFS_LOCK) {
            val order = getRawNotificationWalletOrder().toMutableList()
            // 确保与 showTotal 标记一致
            if (showTotalBalanceInNotification && KEY_NOTIFICATION_TOTAL !in order) {
                order.add(0, KEY_NOTIFICATION_TOTAL)
                setNotificationWalletOrder(order)
            } else if (!showTotalBalanceInNotification) {
                order.remove(KEY_NOTIFICATION_TOTAL)
            }
            order
        }
    }

    /** 读取原始排序列表（不做 total 一致性修正）。 */
    private fun getRawNotificationWalletOrder(): List<String> {
        val raw = prefs.getString(KEY_NOTIFICATION_WALLET_ORDER, null)
        if (raw != null) {
            return try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<String>>(raw)
            } catch (e: Exception) {
                    Logger.w(TAG, "Failed to parse notification wallet order: ${e.message}")
                    emptyList()
                }
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
        synchronized(WIDGET_PREFS_LOCK) {
            check(prefs.edit().putNotificationWalletOrder(order).commit())
        }
    }

    private fun SharedPreferences.Editor.putNotificationWalletOrder(
        order: List<String>
    ): SharedPreferences.Editor {
        val raw = order.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        return putString(KEY_NOTIFICATION_WALLET_ORDER, raw)
    }

    /** 总余额是否在排序列表中。 */
    fun isTotalInNotification(): Boolean = showTotalBalanceInNotification

    /** 检查指定账户+币种是否在通知栏展示列表中。 */
    fun isNotificationWalletSelected(accountId: String, currency: String): Boolean {
        return AlertIdentity(accountId, currency).storageSuffix in getNotificationWalletOrder()
    }

    /** 设置指定账户+币种的通知栏展示。true=加入末尾，false=移除。 */
    fun setNotificationWalletSelected(accountId: String, currency: String, selected: Boolean) {
        synchronized(WIDGET_PREFS_LOCK) {
            val identity = AlertIdentity(accountId, currency)
            val key = identity.storageSuffix
            val order = getRawNotificationWalletOrder().toMutableList()
            if (selected && key !in order) {
                order.add(key)
            } else if (!selected) {
                order.remove(key)
            }
            check(
                prefs.edit()
                    .putNotificationWalletOrder(order)
                    .putBoolean("${KEY_NOTIFICATION_SELECTED}_${identity.storageSuffix}", selected)
                    .commit()
            )
        }
    }

    /** 重置排序列表，只保留布尔标记为 true 的条目 */
    fun resetNotificationWalletOrder() {
        synchronized(WIDGET_PREFS_LOCK) {
            val newOrder = mutableListOf<String>()

            // 检查所有布尔标记
            for (key in prefs.all.keys) {
                if (key.startsWith("${KEY_NOTIFICATION_SELECTED}_")) {
                    val suffix = key.removePrefix("${KEY_NOTIFICATION_SELECTED}_")
                    if (prefs.getBoolean(key, false)) {
                        // 只保留16位ID的条目（新版格式）
                        val parts = suffix.split("_", limit = 2)
                        if (parts.size == 2 && parts[0].length == 16) {
                            newOrder.add(suffix)
                        }
                    }
                }
            }

            setNotificationWalletOrder(newOrder)
        }
    }

    /** 清理排序列表中的无效条目（保留16位ID的条目） */
    fun cleanupInvalidEntries() {
        synchronized(WIDGET_PREFS_LOCK) {
            val order = getRawNotificationWalletOrder().toMutableList()
            val cleaned = order.filter { key ->
                if (key == KEY_NOTIFICATION_TOTAL) {
                    true
                } else {
                    val parts = key.split("_", limit = 2)
                    parts.size == 2 && parts[0].length == 16
                }
            }
            if (cleaned.size != order.size) {
                Logger.w(TAG, "Cleaned ${order.size - cleaned.size} invalid entries from notification order")
                setNotificationWalletOrder(cleaned)
            }
        }
    }

    /** 清理所有旧版8位ID的数据 */
    fun cleanupLegacyIdData() {
        synchronized(WIDGET_PREFS_LOCK) {
            val editor = prefs.edit()
            var cleanedCount = 0

            // 需要清理的key前缀
            val prefixes = listOf(
                KEY_ALERT_ENABLED,
                KEY_CHANGE_ALERT_ENABLED,
                KEY_LAST_ALERTED_BALANCE,
                KEY_PREVIOUS_BALANCE,
                KEY_PREVIOUS_BALANCE_TIME,
                KEY_LAST_CHANGE_ALERTED_BALANCE,
                KEY_LAST_CHANGE_ALERTED_TIME,
                KEY_SNOOZE_UNTIL,
                KEY_NOTIFICATION_SELECTED
            )

            for (key in prefs.all.keys) {
                for (prefix in prefixes) {
                    if (key.startsWith("${prefix}_")) {
                        val suffix = key.removePrefix("${prefix}_")
                        val parts = suffix.split("_", limit = 2)
                        // 检查是否是旧版8位ID
                        if (parts.isNotEmpty() &&
                            parts[0].length == 8 &&
                            parts[0] != KEY_NOTIFICATION_TOTAL
                        ) {
                            editor.remove(key)
                            cleanedCount++
                        }
                    }
                }
            }

            if (cleanedCount > 0) {
                check(editor.commit())
                Logger.w(TAG, "Cleaned $cleanedCount legacy 8-bit ID entries")
            }
        }
    }

    /** 将指定条目在排序中上移一位（accountId=TOTAL_KEY 表示总余额）。 */
    fun moveNotificationWalletUp(accountId: String, currency: String) {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) {
            KEY_NOTIFICATION_TOTAL
        } else {
            AlertIdentity(accountId, currency).storageSuffix
        }
        moveEntryUp(key)
    }

    /** 将指定条目在排序中下移一位。 */
    fun moveNotificationWalletDown(accountId: String, currency: String) {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) {
            KEY_NOTIFICATION_TOTAL
        } else {
            AlertIdentity(accountId, currency).storageSuffix
        }
        moveEntryDown(key)
    }

    private fun moveEntryUp(key: String) {
        synchronized(WIDGET_PREFS_LOCK) {
            val order = getNotificationWalletOrder().toMutableList()
            val idx = order.indexOf(key)
            if (idx > 0) {
                order.removeAt(idx)
                order.add(idx - 1, key)
                setNotificationWalletOrder(order)
            }
        }
    }

    private fun moveEntryDown(key: String) {
        synchronized(WIDGET_PREFS_LOCK) {
            val order = getNotificationWalletOrder().toMutableList()
            val idx = order.indexOf(key)
            if (idx >= 0 && idx < order.size - 1) {
                order.removeAt(idx)
                order.add(idx + 1, key)
                setNotificationWalletOrder(order)
            }
        }
    }

    /** 获取指定条目在排序中的位置（0-based），未选中返回 -1。accountId=TOTAL_KEY 查总余额。 */
    fun getNotificationWalletPosition(accountId: String, currency: String): Int {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) {
            KEY_NOTIFICATION_TOTAL
        } else {
            AlertIdentity(accountId, currency).storageSuffix
        }
        return getNotificationWalletOrder().indexOf(key)
    }

    /** 获取指定条目在选中钱包列表中的位置（0-based），未选中返回 -1。 */
    fun getSelectedWalletPosition(accountId: String, currency: String): Int {
        val key = if (accountId == KEY_NOTIFICATION_TOTAL) {
            KEY_NOTIFICATION_TOTAL
        } else {
            AlertIdentity(accountId, currency).storageSuffix
        }
        return getNotificationWalletOrder().indexOf(key)
    }

    /** 获取选中钱包的总数（含总余额条目）。 */
    fun getSelectedWalletCount(): Int {
        return getNotificationWalletOrder().size
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
        synchronized(WIDGET_PREFS_LOCK) {
            val order = selections.map {
                AlertIdentity(it.accountId, it.currency).storageSuffix
            }
            val editor = prefs.edit().putNotificationWalletOrder(order)
            for (s in selections) {
                editor.putBoolean(
                    "${KEY_NOTIFICATION_SELECTED}_${AlertIdentity(s.accountId, s.currency).storageSuffix}",
                    true
                )
            }
            check(editor.commit())
        }
    }

    companion object {
        private val WIDGET_PREFS_LOCK = Any()
        private const val TAG = "WidgetPrefs"
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
        const val KEY_ALERT_PAIR_STATE_MIGRATED = "alert_pair_state_migrated_v1"
        const val KEY_SNOOZE_UNTIL = "snooze_until"
        const val KEY_SNOOZE_DURATION_MINUTES = "snooze_duration_minutes"
        const val DEFAULT_SNOOZE_MINUTES = 60
        const val KEY_NOTIFICATION_SHOW_TOTAL = "notification_show_total"
        const val KEY_NOTIFICATION_SELECTED = "notification_selected"
        const val KEY_NOTIFICATION_WALLET_ORDER = "notification_wallet_order"
        const val KEY_NOTIFICATION_TOTAL = "__total__"
        const val KEY_LANGUAGE = "pref_language"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        const val KEY_NOTIFICATION_PERMISSION_PERMANENTLY_DENIED = "notification_permission_permanently_denied"

        private val PAIR_STATE_PREFIXES = listOf(
            KEY_LAST_ALERTED_BALANCE,
            KEY_PREVIOUS_BALANCE,
            KEY_PREVIOUS_BALANCE_TIME,
            KEY_LAST_CHANGE_ALERTED_BALANCE,
            KEY_LAST_CHANGE_ALERTED_TIME
        )
        private val LEGACY_ALERT_STATE_PREFIXES = PAIR_STATE_PREFIXES
            .sortedByDescending(String::length)
        private val PAIR_ENABLE_PREFIXES = listOf(
            KEY_ALERT_ENABLED,
            KEY_CHANGE_ALERT_ENABLED
        )
        private val PAIR_ALERT_PREFIXES =
            PAIR_STATE_PREFIXES + PAIR_ENABLE_PREFIXES + KEY_NOTIFICATION_SELECTED
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
