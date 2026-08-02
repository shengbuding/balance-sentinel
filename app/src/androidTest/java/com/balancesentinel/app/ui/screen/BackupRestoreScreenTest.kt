package com.balancesentinel.app.ui.screen

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.balance.ScriptInspection
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.AppConfig
import com.balancesentinel.app.data.repository.BackupImportPlanner
import com.balancesentinel.app.data.repository.ConfigSettings
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupRestoreScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var accountStorage: SharedPreferences
    private lateinit var manager: ApiKeyManager
    private lateinit var widgetPrefs: WidgetPrefs
    private lateinit var viewModel: DataManagementViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        accountStorage = app.getSharedPreferences(
            "backup-screen-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        manager = ApiKeyManager(app, accountStorage)
        widgetPrefs = WidgetPrefs(app)
        widgetPrefs.resetAll()
        val planner = BackupImportPlanner(manager, widgetPrefs) { _, _ ->
            ScriptInspection(null, emptySet(), staticallyDeterminable = true)
        }
        viewModel = DataManagementViewModel(app, manager, widgetPrefs, planner)
    }

    @After
    fun tearDown() {
        accountStorage.edit().clear().commit()
        widgetPrefs.resetAll()
    }

    @Test
    fun selectingConfigurationFileShowsPreviewWithoutPersistence() = runBlocking {
        // Mutation caught: the file-selection path writes accounts/settings before the preview action.
        val local = AccountInfo(LOCAL_ID, "Local", LOCAL_KEY)
        manager.replaceAll(listOf(local))
        widgetPrefs.refreshIntervalSeconds = 222
        val config = config(
            credentialsIncluded = false,
            accounts = listOf(local.copy(label = "Updated", apiKey = "")),
            settings = settings(77)
        )
        val file = java.io.File(app.cacheDir, "preview-${System.nanoTime()}.json")
        file.writeText(Json { encodeDefaults = true }.encodeToString(config))

        viewModel.previewConfiguration(Uri.fromFile(file))

        assertEquals(listOf(local), manager.getAccounts())
        assertEquals(222, widgetPrefs.refreshIntervalSeconds)
        composeRule.setContent {
            DeepSeekBalanceTheme {
                BackupRestoreScreen(viewModel = viewModel, onBack = {})
            }
        }
        composeRule.onNodeWithTag("config_import_preview").assertIsDisplayed()
    }

    @Test
    fun replaceRequiresPreviewApplyAndSeparateDestructiveConfirmation() = runBlocking {
        // Mutation caught: Replace All commits after the preview action or stacks both confirmation dialogs.
        val local = AccountInfo(OLD_ID, "Old", OLD_KEY)
        val replacement = AccountInfo(NEW_ID, "New", NEW_KEY)
        manager.replaceAll(listOf(local))
        viewModel.previewConfiguration(config(true, listOf(replacement)))
        composeRule.setContent {
            DeepSeekBalanceTheme {
                BackupRestoreScreen(viewModel = viewModel, onBack = {})
            }
        }

        composeRule.onNodeWithTag("config_import_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("import_mode_replace").performClick()
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.pendingImportPlan?.mode ==
                com.balancesentinel.app.data.repository.ImportMode.REPLACE_ALL
        }
        composeRule.onNodeWithTag("import_apply").performClick()

        composeRule.onNodeWithTag("replace_confirm_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("config_import_preview").assertDoesNotExist()
        assertEquals(listOf(local), manager.getAccounts())

        composeRule.onNodeWithTag("replace_confirm_apply").performClick()
        composeRule.waitUntil(5_000) { manager.getAccounts().singleOrNull()?.id == NEW_ID }
        assertEquals(
            listOf(replacement.copy(usageScriptEnabled = false)),
            manager.getAccounts()
        )
    }

    private fun config(
        credentialsIncluded: Boolean,
        accounts: List<AccountInfo>,
        settings: ConfigSettings = settings()
    ) = AppConfig(
        version = 2,
        credentialsIncluded = credentialsIncluded,
        exportedAt = "2026-08-01T00:00:00",
        appVersion = "2.0",
        accounts = accounts,
        settings = settings
    )

    private fun settings(refreshIntervalSeconds: Int = 30) = ConfigSettings(
        refreshIntervalSeconds = refreshIntervalSeconds,
        alertEnabled = false,
        alertThreshold = 0f,
        changeAlertEnabled = false,
        changeAlertThreshold = 0f,
        changeAlertPeriodMinutes = 60,
        logMaxEntries = 100
    )

    private companion object {
        const val LOCAL_KEY = "sk-local-secret"
        const val LOCAL_ID = "96ed403d28356eeb"
        const val NEW_KEY = "sk-new-complete"
        const val NEW_ID = "7c6888f7ec01a4e6"
        const val OLD_KEY = "sk-old-complete"
        const val OLD_ID = "41afefea72a24e69"
    }
}
