package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

class AlertSettingsLoadingRedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        SettingsRepositoryProvider.resetForTests()
    }

    @Test
    fun `alert settings shows loading marker before Room snapshot is ready`() {
        SettingsRepositoryProvider.factory = { LoadingSettingsRepository() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HomeViewModel(app)

        composeRule.setContent {
            AlertSettingsScreen(viewModel = viewModel, onBack = {})
        }

        composeRule.onNodeWithTag("settings_loading").assertIsDisplayed()
    }

    private class LoadingSettingsRepository : SettingsRepository {
        override val snapshot: StateFlow<SettingsSnapshotState> =
            MutableStateFlow(SettingsSnapshotState.Loading)
        override suspend fun readSnapshot(): SettingsSnapshot = error("not ready")
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) = error("not ready")
        override suspend fun hasPersistedSnapshot(): Boolean = false
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot = error("not ready")
        override suspend fun applyConfigSettings(settings: com.balancesentinel.app.data.repository.ConfigSettings): SettingsSnapshot = error("not ready")
    }
}
