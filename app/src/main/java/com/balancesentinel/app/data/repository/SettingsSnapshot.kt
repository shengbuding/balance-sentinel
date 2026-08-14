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

    /**
     * The single user-facing cadence used by the foreground service and
     * background recovery work. The two persisted columns remain for
     * backwards-compatible database/config reads.
     */
    val sharedRefreshIntervalSeconds: Int
        get() = foregroundMonitoringIntervalSeconds.takeIf { it > 0 }
            ?: backgroundRefreshIntervalSeconds?.takeIf { it > 0 }
            ?: RoomSettingsRepository.DEFAULT_FOREGROUND_INTERVAL_SECONDS

    /** The cadence used by a caller that has no foreground session. */
    val effectiveBackgroundCadenceSeconds: Int?
        get() = backgroundRefreshIntervalSeconds?.let { sharedRefreshIntervalSeconds }

    fun effectiveCadenceSeconds(isForeground: Boolean): Int? =
        if (isForeground) sharedRefreshIntervalSeconds else effectiveBackgroundCadenceSeconds

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
