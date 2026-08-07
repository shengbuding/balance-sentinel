package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.balancesentinel.app.receiver.SnoozeReceiver

/**
 * 余额预警检查器（多账户版）。
 *
 * 规则：
 * - 余额 < 阈值 AND 与上次触发时的余额不同 → 推送预警通知
 * - 余额 ≥ 阈值 → 重置上次触发余额（允许下次跌破再触发）
 * - 余额不变不重复推送
 * - 去重状态按 accountId 隔离
 * - Snooze 期间（默认 60 分钟，可在设置中调整）不推送任何通知
 */
object AlertChecker {

    fun checkPublished(
        context: android.content.Context,
        accountId: String,
        totalBalance: String,
        currency: String,
        label: String = ""
    ): Boolean {
        val repository = SettingsRepositoryProvider.get(context)
        val snapshot = (repository.snapshot.value as? SettingsSnapshotState.Ready)?.value
            ?: return false
        val perAccount = snapshot.accountAlert(accountId, currency)
        if (!(perAccount?.balanceAlertEnabled ?: snapshot.appSettings.alertEnabled)) return false
        val threshold = snapshot.appSettings.alertThreshold
        if (threshold <= 0.0 || snapshot.snoozeUntil(accountId) > System.currentTimeMillis()) return false
        val balance = totalBalance.toDoubleOrNull() ?: return false
        val runtime = snapshot.alertRuntimeState(accountId, currency)
        val lastAlerted = runtime?.lastAlertedBalance
        if (balance < threshold && (lastAlerted == null || kotlin.math.abs(balance - lastAlerted) > 0.001)) {
            NotificationHelper(context).sendLowBalanceAlert(
                accountId,
                balance.toFloat(),
                threshold.toFloat(),
                currency,
                label
            )
            updateRuntime(repository, runtimeState(runtime, accountId, currency).copy(lastAlertedBalance = balance))
            return true
        }
        if (balance >= threshold && lastAlerted != null) {
            updateRuntime(repository, runtimeState(runtime, accountId, currency).copy(lastAlertedBalance = null))
        }
        return false
    }

    fun checkChangePublished(
        context: android.content.Context,
        accountId: String,
        totalBalance: String,
        currency: String,
        label: String = ""
    ): Boolean {
        val repository = SettingsRepositoryProvider.get(context)
        val snapshot = (repository.snapshot.value as? SettingsSnapshotState.Ready)?.value
            ?: return false
        val perAccount = snapshot.accountAlert(accountId, currency)
        if (!(perAccount?.changeAlertEnabled ?: snapshot.appSettings.changeAlertEnabled)) return false
        val threshold = snapshot.appSettings.changeAlertThreshold
        val periodMinutes = snapshot.appSettings.changeAlertPeriodMinutes
        if (threshold <= 0.0 || periodMinutes <= 0 ||
            snapshot.snoozeUntil(accountId) > System.currentTimeMillis()
        ) return false
        val current = totalBalance.toDoubleOrNull() ?: return false
        val runtime = snapshot.alertRuntimeState(accountId, currency)
        val now = System.currentTimeMillis()
        val periodMs = periodMinutes * 60_000L
        val anchor = runtime?.anchorBalance
        val anchorAt = runtime?.anchorAt
        if (anchor == null || anchorAt == null || now - anchorAt > periodMs) {
            updateRuntime(
                repository,
                runtimeState(runtime, accountId, currency).copy(anchorBalance = current, anchorAt = now)
            )
            return false
        }
        val diff = kotlin.math.abs(current - anchor)
        if (diff < threshold) return false
        if (runtime.lastChangeAlertedBalance != null &&
            kotlin.math.abs(current - runtime.lastChangeAlertedBalance) <= 0.001 &&
            runtime.lastChangeAlertedAt != null && now - runtime.lastChangeAlertedAt < periodMs
        ) return false
        NotificationHelper(context).sendChangeAlert(
            accountId,
            current.toFloat(),
            anchor.toFloat(),
            diff.toFloat(),
            periodMinutes,
            currency,
            label
        )
        updateRuntime(
            repository,
            runtimeState(runtime, accountId, currency).copy(
                anchorBalance = current,
                anchorAt = now,
                lastChangeAlertedBalance = current,
                lastChangeAlertedAt = now
            )
        )
        return true
    }

    private fun runtimeState(
        current: AlertRuntimeStateEntity?,
        accountId: String,
        currency: String
    ): AlertRuntimeStateEntity = current ?: AlertRuntimeStateEntity(accountId, currency)

    private fun updateRuntime(repository: SettingsRepository, value: AlertRuntimeStateEntity) {
        CoroutineScope(Dispatchers.IO).launch {
            repository.updateSnapshot { current ->
                current.copy(
                    alertRuntimeStates = current.alertRuntimeStates.filterNot {
                        it.accountId == value.accountId && it.currency == value.currency
                    } + value
                )
            }
        }
    }

    /**
     * 检查余额是否需要预警。
     * @return true 如果发送了通知
     */
    fun check(
        context: android.content.Context, accountId: String,
        totalBalance: String, currency: String, label: String = ""
    ): Boolean {
        val prefs = WidgetPrefs(context)
        if (!prefs.isBalanceAlertEnabled(accountId, currency)) return false

        val threshold = prefs.alertThreshold
        if (threshold <= 0f) return false

        // Snooze 检查
        if (isSnoozed(prefs, accountId)) return false

        val balance = totalBalance.toFloatOrNull() ?: return false
        val lastAlerted = prefs.getLastAlertedBalance(accountId, currency)

        if (balance < threshold) {
            if (kotlin.math.abs(balance - lastAlerted) > 0.001f) {
                val helper = NotificationHelper(context)
                helper.sendLowBalanceAlert(accountId, balance, threshold, currency, label)
                prefs.setLastAlertedBalance(accountId, currency, balance)
                return true
            }
        } else {
            if (lastAlerted >= 0f) {
                prefs.setLastAlertedBalance(accountId, currency, -1f)
            }
        }
        return false
    }

    /**
     * 检查余额异动。
     *
     * 以时间窗口起点的余额为锚点，后续每次轮询都对比当前余额与锚点。
     * 当累计变动超过阈值时立即发出提醒，而非只比较相邻两次轮询。
     * 锚点仅在以下情况更新：首次调用、窗口过期、提醒触发后。
     *
     * @return true 如果发送了通知
     */
    fun checkChange(
        context: android.content.Context, accountId: String,
        totalBalance: String, currency: String, label: String = ""
    ): Boolean {
        val prefs = WidgetPrefs(context)
        if (!prefs.isChangeAlertEnabled(accountId, currency)) return false

        val threshold = prefs.changeAlertThreshold
        val periodMinutes = prefs.changeAlertPeriodMinutes
        if (threshold <= 0f || periodMinutes <= 0) return false

        // Snooze 检查
        if (isSnoozed(prefs, accountId)) return false

        val current = totalBalance.toFloatOrNull() ?: return false
        val anchor = prefs.getPreviousBalance(accountId, currency)       // 窗口锚点余额
        val anchorTime = prefs.getPreviousBalanceTime(accountId, currency)
        val now = System.currentTimeMillis()
        val periodMs = periodMinutes * 60_000L

        // 首次调用或窗口过期 → 重置锚点
        if (anchor < 0f || anchorTime <= 0L || (now - anchorTime) > periodMs) {
            prefs.setPreviousBalance(accountId, currency, current)
            prefs.setPreviousBalanceTime(accountId, currency, now)
            return false
        }

        val diff = kotlin.math.abs(current - anchor)

        if (diff >= threshold) {
            // 去重：时间窗口内相同余额不重复提醒
            val lastTriggered = prefs.getLastChangeAlertedBalance(accountId, currency)
            val lastTriggeredTime = prefs.getLastChangeAlertedTime(accountId, currency)
            if (kotlin.math.abs(current - lastTriggered) <= 0.001f && (now - lastTriggeredTime) < periodMs) return false

            val helper = NotificationHelper(context)
            helper.sendChangeAlert(accountId, current, anchor, diff, periodMinutes, currency, label)
            prefs.setLastChangeAlertedBalance(accountId, currency, current)
            prefs.setLastChangeAlertedTime(accountId, currency, now)
            // 提醒后重置锚点，避免连续重复触发
            prefs.setPreviousBalance(accountId, currency, current)
            prefs.setPreviousBalanceTime(accountId, currency, now)
            return true
        }
        // 关键：锚点保持不变，累积对比直到触发或窗口过期
        return false
    }

    private fun isSnoozed(prefs: WidgetPrefs, accountId: String): Boolean {
        val snoozeUntil = prefs.getSnoozeUntil(accountId)
        return snoozeUntil > System.currentTimeMillis()
    }
}
