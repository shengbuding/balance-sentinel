package com.balancesentinel.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.ui.console.ApiDebugPanel
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsoleAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun debugPanelActionsRemainReachableAtLargeFontScale() {
        val descriptions = listOf(
            R.string.console_copy_all,
            R.string.console_save,
            R.string.console_clear,
            R.string.console_close
        ).map { composeRule.activity.getString(it) }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DeepSeekBalanceTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        ApiDebugPanel(
                            apiLogs = listOf(sampleLog()),
                            onDismiss = {},
                            onClear = {}
                        )
                    }
                }
            }
        }

        descriptions.forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun apiLogExpansionExposesRoleAndLocalizedState() {
        val expand = composeRule.activity.getString(R.string.console_expand)
        val collapse = composeRule.activity.getString(R.string.console_collapse)
        val collapsed = composeRule.activity.getString(R.string.accessibility_collapsed)
        val expanded = composeRule.activity.getString(R.string.accessibility_expanded)
        composeRule.setContent {
            DeepSeekBalanceTheme {
                ApiDebugPanel(apiLogs = listOf(sampleLog()), onDismiss = {}, onClear = {})
            }
        }

        composeRule.onNodeWithContentDescription(expand)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription(collapse)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expanded))
    }

    private fun sampleLog() = ApiDebugEntry(
        accountId = "accessibility-account",
        url = "https://example.test/balance",
        method = "GET",
        requestHeaders = emptyMap(),
        requestBody = null,
        statusCode = 200,
        responseHeaders = emptyMap(),
        responseBody = "{\"remaining\":25}",
        timestamp = System.currentTimeMillis(),
        duration = 10L
    )
}
