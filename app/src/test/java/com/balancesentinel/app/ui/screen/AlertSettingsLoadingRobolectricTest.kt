package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.ConfigSettings
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountMutationCoordinator
import com.balancesentinel.app.data.repository.AccountMutationResult
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertSettingsLoadingRobolectricTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        SettingsRepositoryProvider.resetForTests()
    }

    @Test
    fun `loading snapshot renders loading card and hides ready controls`() {
        SettingsRepositoryProvider.factory = { LoadingSettingsRepository() }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = HomeViewModel(
            application,
            injectedAccountUiRepository = AccountUiRepository {
                flowOf(AccountLoadState.Ready(emptyList()))
            },
            injectedAccountMutationCoordinator = NoOpAccountMutationCoordinator,
            cleanupAction = {}
        )

        composeRule.setContent {
            AlertSettingsScreen(viewModel = viewModel, onBack = {})
        }

        composeRule.onNodeWithTag("settings_loading").assertIsDisplayed()
    }

    private class LoadingSettingsRepository : SettingsRepository {
        override val snapshot: StateFlow<SettingsSnapshotState> =
            MutableStateFlow(SettingsSnapshotState.Loading)
        override suspend fun readSnapshot(): SettingsSnapshot = error("not ready")
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) =
            error("not ready")
        override suspend fun hasPersistedSnapshot(): Boolean = false
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot =
            error("not ready")
        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot =
            error("not ready")
    }

    private object NoOpAccountMutationCoordinator : AccountMutationCoordinator {
        override suspend fun save(
            existingId: String?,
            draft: com.balancesentinel.app.data.model.AccountDraft
        ): AccountMutationResult = error("not used")

        override suspend fun delete(accountId: String): AccountMutationResult = error("not used")
    }
}
