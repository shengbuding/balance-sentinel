package com.balancesentinel.app.testing

import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.repository.ConfigSettings
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MutableSettingsRepository(
    initial: SettingsSnapshot = SettingsSnapshot(AppSettingsEntity(updatedAt = 0L))
) : SettingsRepository {
    private val writeMutex = Mutex()
    private val state = MutableStateFlow<SettingsSnapshotState>(SettingsSnapshotState.Ready(initial))
    override val snapshot: StateFlow<SettingsSnapshotState> = state
    private var revision = 0L

    override suspend fun readSnapshot(): SettingsSnapshot = readySnapshot()

    override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) {
        writeMutex.withLock {
            state.value = SettingsSnapshotState.Ready(snapshot)
            revision++
        }
    }

    override suspend fun hasPersistedSnapshot(): Boolean = true

    override suspend fun updateSnapshot(
        transform: (SettingsSnapshot) -> SettingsSnapshot
    ): SettingsSnapshot = writeMutex.withLock {
        transform(readySnapshot()).also {
            state.value = SettingsSnapshotState.Ready(it)
            revision++
        }
    }

    override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot =
        error("Config imports are not used by this test repository")

    override suspend fun currentRevision(): Long = revision

    private fun readySnapshot(): SettingsSnapshot =
        (state.value as SettingsSnapshotState.Ready).value
}
