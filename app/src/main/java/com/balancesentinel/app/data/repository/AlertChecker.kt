package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Room snapshot backed balance and change alert evaluation. */
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

    /** Compatibility entry point; configuration still comes only from Room. */
    fun check(
        context: android.content.Context,
        accountId: String,
        totalBalance: String,
        currency: String,
        label: String = ""
    ): Boolean = checkPublished(context, accountId, totalBalance, currency, label)

    /** Compatibility forwarding entry point; see [check]. */
    fun checkChange(
        context: android.content.Context,
        accountId: String,
        totalBalance: String,
        currency: String,
        label: String = ""
    ): Boolean = checkChangePublished(context, accountId, totalBalance, currency, label)

    private fun runtimeState(
        current: AlertRuntimeStateEntity?,
        accountId: String,
        currency: String
    ): AlertRuntimeStateEntity = current ?: AlertRuntimeStateEntity(accountId, currency)

    private fun updateRuntime(repository: SettingsRepository, value: AlertRuntimeStateEntity) {
        CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
            repository.updateSnapshot { current ->
                current.copy(
                    alertRuntimeStates = current.alertRuntimeStates.filterNot {
                        it.accountId == value.accountId && it.currency == value.currency
                    } + value
                )
            }
        }
    }
}
