package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import org.junit.Rule
import org.junit.Test

/**
 * SettingsScreen UI tests.
 *
 * Verifies section headers render, navigation callbacks fire, and
 * interactive elements (expand/collapse, toggles) work correctly.
 */
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createViewModel(): HomeViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return HomeViewModel(app)
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId)

    // ═══════════════════════════════════════════════════════════
    // Smoke tests — screen renders without crash
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `settings screen shows toolbar title and back button`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_title)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.settings_back)).assertIsDisplayed()
    }

    @Test
    fun `settings screen shows auto refresh section`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_auto_refresh)).assertIsDisplayed()
    }

    @Test
    fun `settings screen shows alert settings entry`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_alert_entry)).assertIsDisplayed()
    }

    @Test
    fun `about page shows privacy policy row`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_about)).performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_privacy_policy)).assertIsDisplayed()
    }

    @Test
    fun `settings screen shows data management row`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_data_management)).assertIsDisplayed()
    }

    // ═══════════════════════════════════════════════════════════
    // Navigation callback tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `back button triggers onBack callback`() {
        val vm = createViewModel()
        var called = false
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = { called = true },
                onNavigateToLog = {},
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.settings_back)).performClick()
        assert(called) { "onBack should be called" }
    }

    @Test
    fun `log entry row click triggers onNavigateToLog`() {
        val vm = createViewModel()
        var called = false
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = { called = true },
                onNavigateToDataManagement = {},
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_system_status)).performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_log_entry)).performClick()
        assert(called) { "onNavigateToLog should be called" }
    }

    @Test
    fun `data management row click triggers onNavigateToDataManagement`() {
        val vm = createViewModel()
        var called = false
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = { called = true },
                onNavigateToAlertSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_data_management)).performClick()
        assert(called) { "onNavigateToDataManagement should be called" }
    }
}
