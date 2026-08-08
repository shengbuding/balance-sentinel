package com.balancesentinel.app.data.repository

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val snapshot: StateFlow<SettingsSnapshotState>

    suspend fun readSnapshot(): SettingsSnapshot

    suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long)

    suspend fun hasPersistedSnapshot(): Boolean

    suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot

    suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot

    /** Monotonic Room-backed revision used to invalidate stale import previews. */
    suspend fun currentRevision(): Long = 0L

    suspend fun applyConfigImport(
        settings: ConfigSettings,
        persistAccounts: suspend () -> Unit
    ): SettingsSnapshot = error("Atomic configuration import is not supported by this repository")
}

object SettingsRepositoryProvider {
    private val defaultRepositoryFactory: (Context) -> SettingsRepository = { context ->
        RoomSettingsRepository.from(context)
    }

    @Volatile
    private var repositoryFactory: (Context) -> SettingsRepository = defaultRepositoryFactory

    var factory: (Context) -> SettingsRepository
        get() = repositoryFactory
        set(value) {
            synchronized(this) {
                closeInstanceLocked()
                repositoryFactory = value
            }
        }

    @Volatile
    private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
        instance ?: repositoryFactory(context.applicationContext).also { instance = it }
    }

    internal fun resetForTests() {
        synchronized(this) {
            closeInstanceLocked()
            repositoryFactory = defaultRepositoryFactory
        }
    }

    internal fun isUsingDefaultFactoryForTests(): Boolean =
        repositoryFactory === defaultRepositoryFactory

    private fun closeInstanceLocked() {
        val current = instance
        instance = null
        if (current is AutoCloseable) runCatching { current.close() }
    }
}
