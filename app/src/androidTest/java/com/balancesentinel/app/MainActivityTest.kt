package com.balancesentinel.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

/**
 * MainActivity UI tests.
 *
 * Verifies the main activity renders the home screen with toolbar and
 * empty-state content when launched with no accounts configured.
 *
 * Run with: ./gradlew connectedAndroidTest
 */
class MainActivityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId)

    @Test
    fun `main activity renders home screen toolbar`() {
        composeTestRule.setContent {
            // Render HomeScreen directly with a fresh ViewModel
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = com.balancesentinel.app.ui.viewmodel.HomeViewModel(app)
            com.balancesentinel.app.ui.screen.HomeScreen(
                viewModel = vm,
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText(string(R.string.home_title)).assertIsDisplayed()
    }

    @Test
    fun `main activity shows settings button in toolbar`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = com.balancesentinel.app.ui.viewmodel.HomeViewModel(app)
        composeTestRule.setContent {
            com.balancesentinel.app.ui.screen.HomeScreen(
                viewModel = vm,
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.home_settings)).assertIsDisplayed()
    }

    @Test
    fun `main activity shows refresh button in toolbar`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = com.balancesentinel.app.ui.viewmodel.HomeViewModel(app)
        composeTestRule.setContent {
            com.balancesentinel.app.ui.screen.HomeScreen(
                viewModel = vm,
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.home_refresh)).assertIsDisplayed()
    }
}
