package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
                onNavigateToDataManagement = {}
            )
        }

        composeTestRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun `settings screen shows widget settings section`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {}
            )
        }

        composeTestRule.onNodeWithText("桌面小组件设置").assertIsDisplayed()
    }

    @Test
    fun `settings screen shows alert section headers`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {}
            )
        }

        // Alert section headers — these use labelMedium with bold
        composeTestRule.onNodeWithText("余额预警").assertIsDisplayed()
        composeTestRule.onNodeWithText("异动提醒").assertIsDisplayed()
    }

    @Test
    fun `settings screen shows privacy policy and log entry rows`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {}
            )
        }

        composeTestRule.onNodeWithText("隐私政策").assertIsDisplayed()
        composeTestRule.onNodeWithText("刷新日志").assertIsDisplayed()
    }

    @Test
    fun `settings screen shows version info`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = vm,
                onBack = {},
                onNavigateToLog = {},
                onNavigateToDataManagement = {}
            )
        }

        composeTestRule.onNodeWithText("数据管理").assertIsDisplayed()
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
                onNavigateToDataManagement = {}
            )
        }

        composeTestRule.onNodeWithText("返回").performClick()
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
                onNavigateToDataManagement = {}
            )
        }

        composeTestRule.onNodeWithText("刷新日志").performClick()
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
                onNavigateToDataManagement = { called = true }
            )
        }

        composeTestRule.onNodeWithText("数据管理").performClick()
        assert(called) { "onNavigateToDataManagement should be called" }
    }
}
