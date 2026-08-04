package com.balancesentinel.app.ui.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.balancesentinel.app.R
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

        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_welcome_desc)).assertIsDisplayed()
    }

    @Test
    fun `page 2 shows feature list after clicking next`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Navigate to page 2
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_features_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_feat_refresh)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_feat_alert)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_feat_widget)).assertIsDisplayed()
    }

    @Test
    fun `page 3 shows get started prompt after clicking next twice`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Page 1 → 2 → 3
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()

        composeTestRule.onNodeWithTag(PAGE_TITLE_TAG)
            .assertTextEquals(context.getString(R.string.onboarding_start_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_start_desc)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertTextEquals(context.getString(R.string.onboarding_get_started))
            .assertIsDisplayed()
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

        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_skip)).assertIsDisplayed()

        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_skip)).assertIsDisplayed()
    }

    @Test
    fun `skip button is hidden on last page`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        // Navigate to page 3
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()

        // Skip should NOT be visible on the last page
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_skip)).assertDoesNotExist()
    }

    @Test
    fun `skip calls onComplete and marks onboarding done`() {
        var completed = false
        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completed = true })
        }

        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_skip)).performClick()

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
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()

        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()

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
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertTextEquals(context.getString(R.string.onboarding_next))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_get_started)).assertDoesNotExist()

        // Page 2: still shows "下一步"
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertTextEquals(context.getString(R.string.onboarding_next))
            .assertIsDisplayed()

        // Page 3: shows "开始使用"
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        composeTestRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertTextEquals(context.getString(R.string.onboarding_get_started))
            .assertIsDisplayed()
    }

    private companion object {
        const val PAGE_TITLE_TAG = "onboarding_page_title"
        const val PRIMARY_ACTION_TAG = "onboarding_primary_action"
    }
}
