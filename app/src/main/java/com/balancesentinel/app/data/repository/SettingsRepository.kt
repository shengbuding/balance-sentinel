package com.balancesentinel.app.data.repository

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val snapshot: StateFlow<SettingsSnapshotState>

    suspend fun readSnapshot(): SettingsSnapshot

    suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long)
}

fun funSettingsRepositoryFactory(block: (Context) -> SettingsRepository): (Context) -> SettingsRepository = block

object SettingsRepositoryProvider {
    @Volatile
    var factory: (Context) -> SettingsRepository = { context ->
        RoomSettingsRepository.from(context)
    }

    fun get(context: Context): SettingsRepository = factory(context)
}
