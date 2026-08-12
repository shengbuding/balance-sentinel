package com.balancesentinel.app.ui

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.repository.ConfigSettings
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.data.update.ApkDownloadRepositoryContract
import com.balancesentinel.app.ui.components.AccountBalanceCard
import com.balancesentinel.app.ui.console.AddPlatformScreen
import com.balancesentinel.app.ui.console.ConsolePlatform
import com.balancesentinel.app.ui.console.ConsoleScreen
import com.balancesentinel.app.ui.console.ConsoleSelectScreen
import com.balancesentinel.app.ui.screen.AlertSettingsScreen
import com.balancesentinel.app.ui.screen.BackupRestoreScreen
import com.balancesentinel.app.ui.screen.DataManagementScreen
import com.balancesentinel.app.ui.screen.OnboardingScreen
import com.balancesentinel.app.ui.screen.SettingsScreen
import com.balancesentinel.app.ui.screen.UPDATE_DIALOG_CONTENT_TAG
import com.balancesentinel.app.ui.screen.UpdateDialog
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.ui.viewmodel.ConsoleUiState
import com.balancesentinel.app.ui.viewmodel.AccountRefreshUiState
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.UpdateDownloadViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.After
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LargeFontWorkflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetSettingsRepository() {
        SettingsRepositoryProvider.resetForTests()
    }

    @Test
    fun onboardingContentRemainsScrollableAtTwoHundredPercentFontScale() = withFontScale(2f) {
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 480) {
                OnboardingScreen(onComplete = {})
            }
        }

        composeRule.onNodeWithTag("onboarding_primary_action").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.onboarding_feat_widget))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_primary_action").assertIsDisplayed()
    }

    @Test
    fun accountErrorCanGrowAndScrollAtTwoHundredPercentFontScale() = withFontScale(2f) {
        val error = "A deliberately long refresh error that must wrap instead of being clipped at large font size."
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 300) {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    AccountBalanceCard(
                        accountLabel = "Large font account label",
                        accountId = "large-font-account",
                        providerType = ProviderType.DEEPSEEK,
                        balance = null,
                        now = System.currentTimeMillis(),
                        onLongPress = {},
                        onEdit = {},
                        onDelete = {},
                        refreshState = AccountRefreshUiState(errorMessage = error)
                    )
                }
            }
        }

        composeRule.onNodeWithText(error).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsKeepsDataAndAlertEntriesReachableAtTwoHundredPercentFontScale() = withFontScale(2f) {
        val viewModel = com.balancesentinel.app.ui.viewmodel.HomeViewModel(
            composeRule.activity.application as android.app.Application
        )
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 480) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onNavigateToLog = {},
                    onNavigateToDataManagement = {},
                    onNavigateToAlertSettings = {}
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_alert_entry))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_data_management))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun alertSettingsKeepsGlobalControlsReachableAtTwoHundredPercentFontScale() = withFontScale(2f) {
        SettingsRepositoryProvider.factory = { ReadySettingsRepository() }
        lateinit var viewModel: com.balancesentinel.app.ui.viewmodel.HomeViewModel
        composeRule.runOnIdle {
            viewModel = com.balancesentinel.app.ui.viewmodel.HomeViewModel(
                composeRule.activity.application as android.app.Application
            )
        }
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 480) {
                AlertSettingsScreen(viewModel = viewModel, onBack = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !viewModel.uiState.value.settingsLoading
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alert_settings_section_global))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alert_settings_snooze_label))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dataHubAndBackupActionsRemainScrollableAtTwoHundredPercentFontScale() = withFontScale(2f) {
        val application = composeRule.activity.application as android.app.Application
        val viewModel = DataManagementViewModel(application)
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 480) {
                var showBackup by remember { mutableStateOf(false) }
                if (showBackup) {
                    BackupRestoreScreen(viewModel = viewModel, onBack = {})
                } else {
                    DataManagementScreen(
                        viewModel = viewModel,
                        onBack = {},
                        onNavigateToBackup = { showBackup = true }
                    )
                }
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.data_nav_backup_title))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNode(
            hasText(composeRule.activity.getString(R.string.data_import_config_btn)) and
                hasClickAction()
        )
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.data_debug_report_btn))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun updateDialogLongNotesScrollAndActionsRemainReachableAtTwoHundredPercentFontScale() =
        withFontScale(2f) {
            val repository = OfflineDownloadRepository()
            val viewModel = UpdateDownloadViewModel(
                composeRule.activity.application as android.app.Application,
                repository
            )
            val skipped = AtomicBoolean(false)
            val reminded = AtomicBoolean(false)
            val release = GitHubRelease(
                tagName = "v999.0.0",
                publishedAt = "2026-08-12T00:00:00Z",
                htmlUrl = "https://example.invalid/releases/v999.0.0",
                body = (1..80).joinToString("\n") { line ->
                    "Release note $line explains a deliberately long offline test change."
                }
            )

            assertTrue(
                "test requires a compact phone viewport",
                composeRule.activity.resources.configuration.screenWidthDp < 600
            )
            composeRule.setContent {
                DeepSeekBalanceTheme {
                    UpdateDialog(
                        release = release,
                        currentVersion = "1.0.0",
                        onDismiss = {},
                        onSkipVersion = { skipped.set(true) },
                        onRemindLater = { reminded.set(true) },
                        downloadViewModel = viewModel
                    )
                }
            }

            val content = composeRule.onNodeWithTag(UPDATE_DIALOG_CONTENT_TAG)
            val initialRange = content.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("long release notes must overflow the dialog body", initialRange.maxValue() > 0f)
            content.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                scrollBy(0f, initialRange.maxValue())
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                content.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value() > 0f
            }

            composeRule.onNode(
                hasText(composeRule.activity.getString(R.string.update_download)) and hasClickAction()
            ).assertIsDisplayed().performClick()
            composeRule.waitUntil { repository.startCount.get() == 1 }
            composeRule.onNode(
                hasText(composeRule.activity.getString(R.string.update_skip_version)) and hasClickAction()
            ).assertIsDisplayed().performClick()
            composeRule.onNode(
                hasText(composeRule.activity.getString(R.string.update_remind_later)) and hasClickAction()
            ).assertIsDisplayed().performClick()

            assertTrue(skipped.get())
            assertTrue(reminded.get())
        }

    @Test
    fun consoleSelectAndAddFlowsKeepSearchAndBackControlsReachableAtLargeFont() = withFontScale(2f) {
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 480) {
                var showAddPlatform by remember { mutableStateOf(false) }
                if (showAddPlatform) {
                    AddPlatformScreen(
                        addedPlatformIds = emptyList(),
                        onAddPreset = {},
                        onAddCustom = {},
                        onBack = { showAddPlatform = false }
                    )
                } else {
                    ConsoleSelectScreen(
                        onSelectPlatform = {},
                        onAddPlatform = { showAddPlatform = true }
                    )
                }
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.console_add_console))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.console_search_hint))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.console_add_custom_platform))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.console_platform_name_label))
            .assertIsDisplayed()
    }

    @Test
    fun invalidConsoleDetailStateRemainsReadableAtTwoHundredPercentFontScale() = withFontScale(2f) {
        val invalidPlatform = ConsolePlatform(
            id = "invalid",
            name = "Invalid platform",
            loginUrl = "not-a-url",
            dashboardUrl = "not-a-url",
            successUrlPatterns = emptyList()
        )
        composeRule.setContent {
            LargeFontSurface(width = 320, height = 480) {
                ConsoleScreen(
                    platform = invalidPlatform,
                    uiState = ConsoleUiState(),
                    onLoginSuccess = { _, _, _ -> },
                    onLogout = {},
                    onBack = {}
                )
            }
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.console_invalid_platform))
            .assertIsDisplayed()
    }

    private inline fun withFontScale(scale: Float, block: () -> Unit) {
        val original = shell("settings get system font_scale").trim()
        try {
            shell("settings put system font_scale $scale")
            waitForFontScale(scale)
            block()
        } finally {
            if (original.toFloatOrNull() != null) {
                shell("settings put system font_scale $original")
                waitForFontScale(original.toFloat())
            } else {
                shell("settings delete system font_scale")
                waitForFontScale(1f)
            }
        }
    }

    private fun waitForFontScale(expected: Float) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val activity = composeRule.activity
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                abs(activity.resources.configuration.fontScale - expected) < 0.01f
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private class ReadySettingsRepository : SettingsRepository {
        private val state = MutableStateFlow<SettingsSnapshotState>(
            SettingsSnapshotState.Ready(SettingsSnapshot(AppSettingsEntity(updatedAt = 0L)))
        )
        override val snapshot: StateFlow<SettingsSnapshotState> = state

        override suspend fun readSnapshot(): SettingsSnapshot = readySnapshot()

        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) {
            state.value = SettingsSnapshotState.Ready(snapshot)
        }

        override suspend fun hasPersistedSnapshot(): Boolean = true

        override suspend fun updateSnapshot(
            transform: (SettingsSnapshot) -> SettingsSnapshot
        ): SettingsSnapshot = transform(readySnapshot()).also {
            state.value = SettingsSnapshotState.Ready(it)
        }

        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot = readySnapshot()

        private fun readySnapshot(): SettingsSnapshot =
            (state.value as SettingsSnapshotState.Ready).value
    }

    private class OfflineDownloadRepository : ApkDownloadRepositoryContract {
        private val latest = MutableStateFlow<DownloadOperationEntity?>(null)
        val startCount = AtomicInteger(0)

        override fun observe(tag: String): Flow<DownloadOperationEntity?> = latest

        override suspend fun start(release: GitHubRelease): DownloadOperationEntity {
            startCount.incrementAndGet()
            return DownloadOperationEntity(
                id = "offline-test",
                ownerId = "offline-owner",
                tag = release.tagName,
                sourceUrl = "https://example.invalid/app.apk",
                temporaryPath = "/offline/app.apk.part",
                targetPath = "/offline/app.apk",
                createdAt = 1L,
                updatedAt = 1L
            )
        }

        override suspend fun cancel(operationId: String) = Unit
    }

    @Composable
    private fun LargeFontSurface(width: Int, height: Int, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            DeepSeekBalanceTheme {
                Box(Modifier.size(width.dp, height.dp), content = { content() })
            }
        }
    }
}
