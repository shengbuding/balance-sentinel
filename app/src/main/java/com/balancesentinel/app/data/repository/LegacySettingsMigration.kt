package com.balancesentinel.app.data.repository

/** Read-only adapter over the legacy preference store used by the migration seam. */
interface LegacySettingsSource {
    fun read(): LegacySettings
}

data class LegacySettings(
    val refreshIntervalSeconds: Int,
    val logMaxEntries: Int,
    val alertEnabled: Boolean,
    val alertThreshold: Float,
    val changeAlertEnabled: Boolean,
    val changeAlertThreshold: Float,
    val changeAlertPeriodMinutes: Int,
    val snoozeDurationMinutes: Int,
    val showTotalBalanceInNotification: Boolean,
    val perCurrencyAlertSettings: List<PerCurrencyAlertSetting>,
    val notificationSelections: List<NotificationWalletSelection>
)

class WidgetPrefsLegacySettingsSource(private val widgetPrefs: WidgetPrefs) : LegacySettingsSource {
    override fun read(): LegacySettings = LegacySettings(
        refreshIntervalSeconds = widgetPrefs.refreshIntervalSeconds,
        logMaxEntries = widgetPrefs.logMaxEntries,
        alertEnabled = widgetPrefs.alertEnabled,
        alertThreshold = widgetPrefs.alertThreshold,
        changeAlertEnabled = widgetPrefs.changeAlertEnabled,
        changeAlertThreshold = widgetPrefs.changeAlertThreshold,
        changeAlertPeriodMinutes = widgetPrefs.changeAlertPeriodMinutes,
        snoozeDurationMinutes = widgetPrefs.snoozeDurationMinutes,
        showTotalBalanceInNotification = widgetPrefs.showTotalBalanceInNotification,
        perCurrencyAlertSettings = widgetPrefs.getAllPerCurrencyAlertSettings().toList(),
        notificationSelections = widgetPrefs.getAllNotificationWalletSelections().toList()
    )
}

/** Migration orchestration is intentionally enabled only by the GREEN wiring. */
class LegacySettingsMigration(
    @Suppress("UNUSED_PARAMETER") private val source: LegacySettingsSource,
    @Suppress("UNUSED_PARAMETER") private val repository: SettingsRepository
) {
    suspend fun migrate(): SettingsSnapshot =
        throw UnsupportedOperationException("Legacy settings migration is not wired")
}
