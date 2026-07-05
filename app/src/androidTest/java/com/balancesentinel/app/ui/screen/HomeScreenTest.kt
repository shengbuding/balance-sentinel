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
 * HomeScreen UI tests.
 *
 * Verifies the home screen renders in its initial empty-account state,
 * the FAB and toolbar buttons are present, and the empty-state hint is shown.
 *
 * Run with: ./gradlew connectedAndroidTest
 */
class HomeScreenTest {

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
    fun `home screen renders toolbar with title`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithText("钱包哨兵").assertIsDisplayed()
    }

    @Test
    fun `home screen shows empty state when no accounts`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithText("还没有监控账户").assertIsDisplayed()
        composeTestRule.onNodeWithText("点击右下角的 + 按钮添加 DeepSeek API Key 开始监控余额").assertIsDisplayed()
    }

    @Test
    fun `home screen has refresh and settings buttons in toolbar`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription("刷新").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("设置").assertIsDisplayed()
    }

    @Test
    fun `home screen has add account FAB`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription("添加账户").assertIsDisplayed()
    }

    @Test
    fun `settings button triggers navigation callback`() {
        val vm = createViewModel()
        var navigated = false
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = { navigated = true })
        }

        composeTestRule.onNodeWithContentDescription("设置").performClick()
        assert(navigated) { "onNavigateToSettings should be called" }
    }

    // ═══════════════════════════════════════════════════════════
    // Add account dialog tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `FAB click opens add account dialog`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription("添加账户").performClick()

        composeTestRule.onNodeWithText("添加 DeepSeek 账户").assertIsDisplayed()
    }

    @Test
    fun `add account dialog has label and key fields`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription("添加账户").performClick()

        composeTestRule.onNodeWithText("账户名称（自定义）").assertIsDisplayed()
        composeTestRule.onNodeWithText("API Key").assertIsDisplayed()
    }

    @Test
    fun `add account dialog has cancel and add buttons`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription("添加账户").performClick()

        composeTestRule.onNodeWithText("取消").assertIsDisplayed()
        composeTestRule.onNodeWithText("添加").assertIsDisplayed()
    }

    @Test
    fun `add account dialog can be dismissed with cancel`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription("添加账户").performClick()
        composeTestRule.onNodeWithText("添加 DeepSeek 账户").assertIsDisplayed()

        composeTestRule.onNodeWithText("取消").performClick()

        // Dialog should be dismissed — empty state should be visible again
        composeTestRule.onNodeWithText("还没有监控账户").assertIsDisplayed()
    }
}
