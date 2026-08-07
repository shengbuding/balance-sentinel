package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity

/** Immutable, published view of every configuration-owned settings table. */
data class SettingsSnapshot(
    val appSettings: AppSettingsEntity,
    val accountAlertSettings: List<AccountAlertSettingEntity> = emptyList(),
    val notificationSelections: List<NotificationWalletSelectionEntity> = emptyList(),
    val alertRuntimeStates: List<AlertRuntimeStateEntity> = emptyList(),
    val snoozes: List<SnoozeStateEntity> = emptyList()
) {
    val backgroundRefreshIntervalSeconds: Int?
        get() = appSettings.backgroundRefreshIntervalSeconds

    val foregroundMonitoringIntervalSeconds: Int
        get() = appSettings.foregroundMonitoringIntervalSeconds

    /** The cadence used by a caller that has no foreground session. */
    val effectiveBackgroundCadenceSeconds: Int?
        get() = backgroundRefreshIntervalSeconds

    fun accountAlert(accountId: String, currency: String): AccountAlertSettingEntity? =
        accountAlertSettings.firstOrNull { it.accountId == accountId && it.currency == currency }

    fun alertRuntimeState(accountId: String, currency: String): AlertRuntimeStateEntity? =
        alertRuntimeStates.firstOrNull { it.accountId == accountId && it.currency == currency }

    fun snoozeUntil(accountId: String): Long =
        snoozes.firstOrNull { it.accountId == accountId }?.snoozedUntil ?: 0L
}

sealed interface SettingsSnapshotState {
    data object Loading : SettingsSnapshotState
    data class Ready(val value: SettingsSnapshot) : SettingsSnapshotState
}
