package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryProviderTest {
    @Test
    fun `test reset closes active repository and restores the default factory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val injected = CloseableSettingsRepository()
        SettingsRepositoryProvider.factory = { injected }

        try {
            assertSame(injected, SettingsRepositoryProvider.get(context))

            SettingsRepositoryProvider.resetForTests()

            assertTrue(injected.closed)
            assertNotSame(injected, SettingsRepositoryProvider.get(context))
        } finally {
            injected.close()
            SettingsRepositoryProvider.factory = { RoomSettingsRepository.from(it) }
            SettingsRepositoryProvider.resetForTests()
        }
    }

    private class CloseableSettingsRepository : SettingsRepository, AutoCloseable {
        private val state = MutableStateFlow<SettingsSnapshotState>(
            SettingsSnapshotState.Ready(SettingsSnapshot(AppSettingsEntity(updatedAt = 0L)))
        )
        override val snapshot = state
        var closed = false
            private set

        override suspend fun readSnapshot(): SettingsSnapshot =
            (state.value as SettingsSnapshotState.Ready).value

        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) {
            state.value = SettingsSnapshotState.Ready(snapshot)
        }

        override suspend fun hasPersistedSnapshot(): Boolean = true

        override suspend fun updateSnapshot(
            transform: (SettingsSnapshot) -> SettingsSnapshot
        ): SettingsSnapshot = transform(readSnapshot()).also {
            state.value = SettingsSnapshotState.Ready(it)
        }

        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot =
            error("not used")

        override fun close() {
            closed = true
        }
    }
}
