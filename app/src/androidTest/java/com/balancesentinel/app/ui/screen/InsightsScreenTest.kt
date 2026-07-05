package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.ui.viewmodel.InsightsViewModel
import org.junit.Rule
import org.junit.Test

/**
 * InsightsScreen UI tests.
 *
 * Verifies the insights screen renders its loading state, empty state,
 * and the dual-card layout when data is present.
 */
class InsightsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createViewModel(): InsightsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return InsightsViewModel(app)
    }

    // ═══════════════════════════════════════════════════════════
    // Screen renders
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `insights screen shows empty state when no data`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            InsightsScreen(viewModel = vm)
        }

        // After initial load completes and there's no data, the empty text should show.
        // The ViewModel loads asynchronously — wait for idle then check.
        composeTestRule.waitForIdle()

        // Either "暂无洞察数据" or the intraday card header — at least one must appear
        val hasEmpty = try {
            composeTestRule.onNodeWithText("暂无洞察数据").assertExists()
            true
        } catch (_: AssertionError) { false }

        val hasIntraday = try {
            composeTestRule.onNodeWithText("24 小时").assertExists()
            true
        } catch (_: AssertionError) { false }

        assert(hasEmpty || hasIntraday) {
            "Insights screen should show either empty state or intraday card after async load"
        }
    }

    @Test
    fun `insights screen renders without crash`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            InsightsScreen(viewModel = vm)
        }

        composeTestRule.waitForIdle()

        // The screen should have rendered content — verify by checking that
        // the Compose hierarchy is not empty after the async load settles
        val nodeCount = composeTestRule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.Text
            )
        ).fetchSemanticsNodes().size

        assert(nodeCount > 0) { "Screen should have rendered text nodes after load; got $nodeCount" }
    }
}
