package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId)

    // ═══════════════════════════════════════════════════════════
    // Smoke tests — screen renders without crash
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `home screen renders toolbar with title`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithText(string(R.string.home_title)).assertIsDisplayed()
    }

    @Test
    fun `home screen shows empty state when no accounts`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithText(string(R.string.home_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_empty_subtitle)).assertIsDisplayed()
    }

    @Test
    fun `home screen has refresh and settings buttons in toolbar`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.home_refresh)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.home_settings)).assertIsDisplayed()
    }

    @Test
    fun `home screen has add account FAB`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithTag(ADD_ACCOUNT_FAB_TAG).assertIsDisplayed()
    }

    @Test
    fun `settings button triggers navigation callback`() {
        val vm = createViewModel()
        var navigated = false
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = { navigated = true })
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.home_settings)).performClick()
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

        composeTestRule.onNodeWithTag(ADD_ACCOUNT_FAB_TAG).performClick()

        composeTestRule.onNodeWithText(string(R.string.add_account_key_label)).assertIsDisplayed()
    }

    @Test
    fun `add account dialog has label and key fields`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithTag(ADD_ACCOUNT_FAB_TAG).performClick()

        composeTestRule.onNodeWithText(string(R.string.add_account_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.add_account_key_label)).assertIsDisplayed()
    }

    @Test
    fun `add account dialog has cancel and add buttons`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithTag(ADD_ACCOUNT_FAB_TAG).performClick()

        composeTestRule.onNodeWithText(string(R.string.home_cancel)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_add)).assertIsDisplayed()
    }

    @Test
    fun `add account dialog can be dismissed with cancel`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithTag(ADD_ACCOUNT_FAB_TAG).performClick()
        composeTestRule.onNodeWithText(string(R.string.add_account_key_label)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.home_cancel)).performClick()

        // Dialog should be dismissed — empty state should be visible again
        composeTestRule.onNodeWithText(string(R.string.home_empty_title)).assertIsDisplayed()
    }

    private companion object {
        const val ADD_ACCOUNT_FAB_TAG = "add_account_fab"
    }
}
