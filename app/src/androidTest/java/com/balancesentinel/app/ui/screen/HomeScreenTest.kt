package com.balancesentinel.app.ui.screen

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.ui.components.EditAccountDialog
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

    @Test
    fun `add custom account dialog follows English resource locale`() {
        val vm = createViewModel()
        setEnglishContent {
            HomeScreen(viewModel = vm, onNavigateToSettings = {})
        }

        composeTestRule.onNodeWithTag(ADD_ACCOUNT_FAB_TAG).performClick()
        composeTestRule.onNodeWithTag(PROVIDER_SELECTOR_TAG).performClick()
        composeTestRule.onNodeWithTag(CUSTOM_PROVIDER_OPTION_TAG)
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText("Provider").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom API Key").assertIsDisplayed()
        composeTestRule.onNodeWithText("API Base URL")
            .performScrollTo()
            .performTextInput("not-a-url")
        composeTestRule.onNodeWithText("URL must be a valid http:// or https:// address")
            .assertIsDisplayed()
    }

    @Test
    fun `edit custom account dialog follows English resource locale`() {
        setEnglishContent {
            EditAccountDialog(
                account = AccountInfo(
                    id = "custom-account",
                    label = "Custom Account",
                    apiKey = "custom-token",
                    providerType = ProviderType.CUSTOM,
                    extraSettings = mapOf("baseUrl" to "https://api.example.com/v1")
                ),
                onDismiss = {},
                onConfirm = {}
            )
        }

        composeTestRule.onNodeWithText("Edit Account").assertIsDisplayed()
        composeTestRule.onNodeWithText("Provider").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom balance query script")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Query Script")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Supports template variables: {{apiKey}}, {{baseUrl}}\n" +
                "Return format: { remaining, unit, isValid }"
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Load Preset Script")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithText("Generic Script").assertIsDisplayed()
    }

    private companion object {
        const val ADD_ACCOUNT_FAB_TAG = "add_account_fab"
        const val PROVIDER_SELECTOR_TAG = "account_provider_selector"
        const val CUSTOM_PROVIDER_OPTION_TAG = "account_provider_option_custom"
    }
}
