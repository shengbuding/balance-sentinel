package com.balancesentinel.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun setEnglishContent(content: @Composable () -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en"))
        }
        val englishContext = base.createConfigurationContext(configuration)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalContext provides englishContext,
                LocalConfiguration provides configuration,
                content = content
            )
        }
    }

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

    @Test
    fun `primary navigation does not leak Chinese in English locale`() {
        setEnglishContent {
            AppNavigationBar(currentScreen = Screen.HOME, onScreenSelected = {})
        }

        composeTestRule.onAllNodesWithText("控制台").assertCountEquals(0)
        composeTestRule.onNodeWithText("Console").assertIsDisplayed()
    }
}
