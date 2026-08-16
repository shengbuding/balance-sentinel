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
    val notificationSelections: List<NotificationWalletSelection>,
    /** Number of legacy wallet entries before the virtual total row. */
    val notificationTotalDisplayOrder: Int = 0
)

class WidgetPrefsLegacySettingsSource(private val widgetPrefs: WidgetPrefs) : LegacySettingsSource {
    override fun read(): LegacySettings {
        val order = widgetPrefs.getNotificationWalletOrder()
        fun parseSelection(key: String): NotificationWalletSelection? {
            if (key == WidgetPrefs.KEY_NOTIFICATION_TOTAL) return null
            val parts = key.split("_", limit = 2)
            val accountId = parts.getOrNull(0).orEmpty()
            val currency = parts.getOrNull(1).orEmpty()
            return if (accountId.isBlank() || currency.isBlank()) null
            else NotificationWalletSelection(accountId, currency)
        }
        val selections = order.mapNotNull(::parseSelection)
        val totalIndex = order.indexOf(WidgetPrefs.KEY_NOTIFICATION_TOTAL)
            .takeIf { it >= 0 }
            ?: 0
        return LegacySettings(
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
            notificationSelections = selections,
            notificationTotalDisplayOrder = order.take(totalIndex).mapNotNull(::parseSelection).size
        )
    }
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
        val resolvedNotificationSelections = legacy.notificationSelections.map { selection ->
            resolveAccountId(selection.accountId)?.let { selection.copy(accountId = it) }
        }
        val totalDisplayOrder = resolvedNotificationSelections
            .take(legacy.notificationTotalDisplayOrder.coerceIn(0, resolvedNotificationSelections.size))
            .count { it != null }
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
                updatedAt = publishedAt,
                notificationTotalDisplayOrder = totalDisplayOrder
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
            notificationSelections = resolvedNotificationSelections
                .filterNotNull()
                .map {
                    com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity(
                        accountId = it.accountId,
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
