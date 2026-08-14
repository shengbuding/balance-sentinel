package com.balancesentinel.app.data.repository

/** Read-only adapter over the legacy preference store used by the migration seam. */
fun interface LegacySettingsSource {
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
    private val source: LegacySettingsSource,
    private val repository: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val resolveAccountId: suspend (String) -> String? = { it }
) {
    suspend fun migrate(): SettingsSnapshot {
        if (repository.hasPersistedSnapshot()) return repository.readSnapshot()
        val legacy = source.read()
        val oldInterval = legacy.refreshIntervalSeconds.coerceAtLeast(1)
        val publishedAt = now()
        val snapshot = SettingsSnapshot(
            appSettings = com.balancesentinel.app.data.local.settings.AppSettingsEntity(
                backgroundRefreshIntervalSeconds = oldInterval,
                foregroundMonitoringIntervalSeconds = oldInterval,
                alertEnabled = legacy.alertEnabled,
                alertThreshold = legacy.alertThreshold.toDouble(),
                changeAlertEnabled = legacy.changeAlertEnabled,
                changeAlertThreshold = legacy.changeAlertThreshold.toDouble(),
                changeAlertPeriodMinutes = legacy.changeAlertPeriodMinutes,
                logMaxEntries = legacy.logMaxEntries,
                snoozeDurationMinutes = legacy.snoozeDurationMinutes,
                showTotalBalanceInNotification = legacy.showTotalBalanceInNotification,
                updatedAt = publishedAt
            ),
            accountAlertSettings = legacy.perCurrencyAlertSettings.mapNotNull {
                val accountId = resolveAccountId(it.accountId) ?: return@mapNotNull null
                com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity(
                    accountId = accountId,
                    currency = it.currency,
                    balanceAlertEnabled = it.balanceAlertEnabled,
                    changeAlertEnabled = it.changeAlertEnabled
                )
            },
            notificationSelections = legacy.notificationSelections
                .filter { it.accountId.isNotBlank() && it.accountId != WidgetPrefs.KEY_NOTIFICATION_TOTAL }
                .mapNotNull {
                    val accountId = resolveAccountId(it.accountId) ?: return@mapNotNull null
                    com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity(
                        accountId = accountId,
                        currency = it.currency,
                        displayOrder = 0
                    )
                }
                .mapIndexed { index, value -> value.copy(displayOrder = index) }
        )
        repository.publishSnapshot(snapshot, publishedAt)
        return repository.readSnapshot()
    }
}
