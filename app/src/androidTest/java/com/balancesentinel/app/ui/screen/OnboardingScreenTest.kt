package com.balancesentinel.app.ui.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.balancesentinel.app.util.OnboardingHelper
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * OnboardingScreen UI tests — 3-page pager flow.
 *
 * Verifies navigation (next/skip/get-started), page content, and
 * dot indicator behavior, independent of any ViewModel.
 */
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Reset onboarding so the screen shows on each test
        OnboardingHelper.reset(context)
    }

    @After
    fun tearDown() {
        OnboardingHelper.reset(context)
    }

    // ═══════════════════════════════════════════════════════════
    // Page content tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `page 1 shows welcome title and description`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        composeTestRule.onNodeWithText("欢迎使用钱包哨兵").assertIsDisplayed()
        composeTestRule.onNodeWithText("实时监控 DeepSeek API 余额").assertIsDisplayed()
    }

    @Test
    fun `page 2 shows feature list after clicking next`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Navigate to page 2
        composeTestRule.onNodeWithText("下一步").performClick()

        composeTestRule.onNodeWithText("核心功能").assertIsDisplayed()
        composeTestRule.onNodeWithText("自动刷新余额，无需手动操作").assertIsDisplayed()
        composeTestRule.onNodeWithText("余额不足时推送通知提醒").assertIsDisplayed()
        composeTestRule.onNodeWithText("桌面小组件随时查看余额").assertIsDisplayed()
    }

    @Test
    fun `page 3 shows get started prompt after clicking next twice`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Page 1 → 2 → 3
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("下一步").performClick()

        composeTestRule.onNodeWithText("准备就绪").assertIsDisplayed()
        composeTestRule.onNodeWithText("添加您的 DeepSeek API Key 即可开始监控").assertIsDisplayed()
        composeTestRule.onNodeWithText("开始使用").assertIsDisplayed()
    }

    // ═══════════════════════════════════════════════════════════
    // Navigation tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `skip button is visible on page 1 and 2`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        composeTestRule.onNodeWithText("跳过").assertIsDisplayed()

        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("跳过").assertIsDisplayed()
    }

    @Test
    fun `skip button is hidden on last page`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Navigate to page 3
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("下一步").performClick()

        // Skip should NOT be visible on the last page
        composeTestRule.onNodeWithText("跳过").assertDoesNotExist()
    }

    @Test
    fun `skip calls onComplete and marks onboarding done`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        composeTestRule.onNodeWithText("跳过").performClick()

        assert(completed) { "onComplete should be called when skip is clicked" }
        assert(!OnboardingHelper.shouldShow(context)) { "Onboarding should be marked completed" }
    }

    @Test
    fun `get started on last page calls onComplete and marks onboarding done`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Navigate to last page
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("下一步").performClick()

        composeTestRule.onNodeWithText("开始使用").performClick()

        assert(completed) { "onComplete should be called when get-started is clicked" }
        assert(!OnboardingHelper.shouldShow(context)) { "Onboarding should be marked completed" }
    }

    @Test
    fun `next button changes text to get started on last page`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Page 1: shows "下一步"
        composeTestRule.onNodeWithText("下一步").assertIsDisplayed()
        composeTestRule.onNodeWithText("开始使用").assertDoesNotExist()

        // Page 2: still shows "下一步"
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("下一步").assertIsDisplayed()

        // Page 3: shows "开始使用"
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("开始使用").assertIsDisplayed()
    }
}
