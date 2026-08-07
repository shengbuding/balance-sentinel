package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room-backed repository seam. Wiring and publication are completed in Task 7 GREEN. */
class RoomSettingsRepository(
    private val database: WalletDatabase,
    private val scope: CoroutineScope? = null
) : SettingsRepository {
    private val writeMutex = Mutex()
    private val state = MutableStateFlow<SettingsSnapshotState>(SettingsSnapshotState.Loading)
    override val snapshot: StateFlow<SettingsSnapshotState> = state

    init {
        scope?.launch {
            merge(
                database.appSettingsDao().observe().map { Unit },
                database.settingsDao().observeAccountAlertSettings().map { Unit },
                database.settingsDao().observeNotificationSelections().map { Unit },
                database.settingsDao().observeAlertRuntimeStates().map { Unit },
                database.settingsDao().observeSnoozes().map { Unit }
            ).collect {
                state.value = SettingsSnapshotState.Ready(loadSnapshot())
            }
        }
    }

    override suspend fun readSnapshot(): SettingsSnapshot = loadSnapshot().also {
        state.value = SettingsSnapshotState.Ready(it)
    }

    override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) {
        writeMutex.withLock {
            publishSnapshotLocked(snapshot, publishedAt)
        }
    }

    private suspend fun publishSnapshotLocked(snapshot: SettingsSnapshot, publishedAt: Long) {
        val backgroundInterval = snapshot.backgroundRefreshIntervalSeconds
        require(backgroundInterval == null ||
            backgroundInterval >= MIN_BACKGROUND_INTERVAL_SECONDS) {
            "Background refresh interval must be disabled or at least 900 seconds"
        }
        require(snapshot.foregroundMonitoringIntervalSeconds > 0) {
            "Foreground monitoring interval must be positive"
        }
        val selections = snapshot.notificationSelections.mapIndexed { index, value ->
            value.copy(displayOrder = index)
        }
        val knownAccountIds = database.accountDao().getAllForMigration().map { it.id }.toSet()
        val accountAlerts = snapshot.accountAlertSettings.filter { it.accountId in knownAccountIds }
        val notificationRows = selections.filter { it.accountId in knownAccountIds }
            .mapIndexed { index, value -> value.copy(displayOrder = index) }
        database.withTransaction {
            val app = snapshot.appSettings
            database.appSettingsDao().upsert(
                backgroundRefreshIntervalSeconds = app.backgroundRefreshIntervalSeconds,
                foregroundMonitoringIntervalSeconds = app.foregroundMonitoringIntervalSeconds,
                alertEnabled = app.alertEnabled,
                alertThreshold = app.alertThreshold,
                changeAlertEnabled = app.changeAlertEnabled,
                changeAlertThreshold = app.changeAlertThreshold,
                changeAlertPeriodMinutes = app.changeAlertPeriodMinutes,
                logMaxEntries = app.logMaxEntries,
                snoozeDurationMinutes = app.snoozeDurationMinutes,
                showTotalBalanceInNotification = app.showTotalBalanceInNotification,
                updatedAt = publishedAt
            )
            database.settingsDao().replaceAccountAlertSettings(accountAlerts)
            database.settingsDao().replaceNotificationSelections(notificationRows)
            database.settingsDao().replaceAlertRuntimeStates(snapshot.alertRuntimeStates)
            database.settingsDao().replaceSnoozes(snapshot.snoozes.filter { it.snoozedUntil > 0L })
            val metadataDao = database.appMetadataDao()
            val metadata = metadataDao.get() ?: run {
                metadataDao.ensureSingleton(publishedAt)
                requireNotNull(metadataDao.get())
            }
            check(metadataDao.incrementRevisionIfCurrent(metadata.localRevision, publishedAt) == 1) {
                "Settings publication has a stale local revision"
            }
        }
        state.value = SettingsSnapshotState.Ready(loadSnapshot())
    }

    override suspend fun hasPersistedSnapshot(): Boolean = database.appSettingsDao().get() != null

    override suspend fun currentRevision(): Long =
        database.appMetadataDao().get()?.localRevision ?: 0L

    override suspend fun updateSnapshot(
        transform: (SettingsSnapshot) -> SettingsSnapshot
    ): SettingsSnapshot = writeMutex.withLock {
        val updated = transform(loadSnapshot())
        publishSnapshotLocked(updated, System.currentTimeMillis())
        loadSnapshot().also { state.value = SettingsSnapshotState.Ready(it) }
    }

    override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot =
        updateSnapshot { current -> current.withConfigSettings(settings) }

    override suspend fun applyConfigImport(
        settings: ConfigSettings,
        persistAccounts: suspend () -> Unit
    ): SettingsSnapshot = writeMutex.withLock {
        val previous = loadSnapshot()
        val previousRevision = database.appMetadataDao().get()?.localRevision ?: 0L
        try {
            persistAccounts()
            publishSnapshotLocked(previous.withConfigSettings(settings), System.currentTimeMillis())
            loadSnapshot().also { state.value = SettingsSnapshotState.Ready(it) }
        } catch (failure: Throwable) {
            runCatching {
                publishSnapshotLocked(previous, previous.appSettings.updatedAt)
                database.appMetadataDao().restoreRevision(previousRevision, previous.appSettings.updatedAt)
            }.onFailure { rollbackError -> failure.addSuppressed(rollbackError) }
            throw failure
        }
    }

    private fun SettingsSnapshot.withConfigSettings(settings: ConfigSettings): SettingsSnapshot {
        val legacyInterval = settings.refreshIntervalSeconds.coerceAtLeast(1)
        val background = settings.backgroundRefreshInterval
            ?: legacyInterval.coerceAtLeast(MIN_BACKGROUND_INTERVAL_SECONDS)
        val foreground = settings.foregroundMonitoringInterval
            ?: if (legacyInterval < MIN_BACKGROUND_INTERVAL_SECONDS) {
                legacyInterval
            } else {
                DEFAULT_FOREGROUND_INTERVAL_SECONDS
            }
        return copy(
            appSettings = appSettings.copy(
                backgroundRefreshIntervalSeconds = background,
                foregroundMonitoringIntervalSeconds = foreground,
                alertEnabled = settings.alertEnabled,
                alertThreshold = settings.alertThreshold.toDouble(),
                changeAlertEnabled = settings.changeAlertEnabled,
                changeAlertThreshold = settings.changeAlertThreshold.toDouble(),
                changeAlertPeriodMinutes = settings.changeAlertPeriodMinutes,
                logMaxEntries = settings.logMaxEntries,
                snoozeDurationMinutes = settings.snoozeDurationMinutes,
                showTotalBalanceInNotification = settings.showTotalBalance
            ),
            accountAlertSettings = settings.perCurrencyAlertSettings.map {
                com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity(
                    it.accountId,
                    it.currency,
                    it.balanceAlertEnabled,
                    it.changeAlertEnabled
                )
            },
            notificationSelections = settings.notificationSelectedWallets.mapIndexed { index, value ->
                com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity(
                    value.accountId,
                    value.currency,
                    index
                )
            }
        )
    }

    private suspend fun loadSnapshot(): SettingsSnapshot = database.withTransaction {
        val app = database.appSettingsDao().get() ?: AppSettingsEntity(updatedAt = 0L)
        SettingsSnapshot(
            appSettings = app,
            accountAlertSettings = database.settingsDao().getAccountAlertSettings().toList(),
            notificationSelections = database.settingsDao().getNotificationSelections().toList(),
            alertRuntimeStates = database.settingsDao().getAlertRuntimeStates().toList(),
            snoozes = database.settingsDao().getSnoozes().toList()
        )
    }

    companion object {
        const val MIN_BACKGROUND_INTERVAL_SECONDS = 900
        const val DEFAULT_FOREGROUND_INTERVAL_SECONDS = 30

        fun from(context: Context): RoomSettingsRepository =
            RoomSettingsRepository(
                WalletDatabaseProvider.get(context),
                CoroutineScope(SupervisorJob() + Dispatchers.IO)
            )
    }
}
