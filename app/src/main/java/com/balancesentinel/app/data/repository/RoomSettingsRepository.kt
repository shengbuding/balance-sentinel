package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Room-backed repository seam. Wiring and publication are completed in Task 7 GREEN. */
class RoomSettingsRepository(
    private val database: WalletDatabase,
    @Suppress("UNUSED_PARAMETER") private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : SettingsRepository {
    private val state = MutableStateFlow<SettingsSnapshotState>(SettingsSnapshotState.Loading)
    override val snapshot: StateFlow<SettingsSnapshotState> = state

    override suspend fun readSnapshot(): SettingsSnapshot = loadSnapshot()

    override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) {
        throw UnsupportedOperationException("Room settings publication is not wired")
    }

    private suspend fun loadSnapshot(): SettingsSnapshot {
        val app = database.appSettingsDao().get()
            ?: com.balancesentinel.app.data.local.settings.AppSettingsEntity(updatedAt = 0L)
        return SettingsSnapshot(
            appSettings = app,
            accountAlertSettings = database.settingsDao().getAccountAlertSettings().toList(),
            notificationSelections = database.settingsDao().getNotificationSelections().toList(),
            alertRuntimeStates = database.settingsDao().getAlertRuntimeStates().toList(),
            snoozes = database.settingsDao().getSnoozes().toList()
        )
    }

    companion object {
        fun from(context: Context): RoomSettingsRepository =
            RoomSettingsRepository(WalletDatabaseProvider.get(context))
    }
}
