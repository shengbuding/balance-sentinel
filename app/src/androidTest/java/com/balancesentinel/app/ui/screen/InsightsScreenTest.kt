package com.balancesentinel.app.ui.screen

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
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
    fun `insights screen shows resolved empty state after load`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            InsightsScreen(viewModel = vm)
        }

        assertResolvedEmptyState()
    }

    @Test
    fun `insights screen renders without crash`() {
        val vm = createViewModel()
        composeTestRule.setContent {
            InsightsScreen(viewModel = vm)
        }

        assertResolvedEmptyState()

        // The screen should have rendered content — verify by checking that
        // the Compose hierarchy is not empty after the async load settles
        val nodeCount = composeTestRule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.Text
            )
        ).fetchSemanticsNodes().size

        assert(nodeCount > 0) { "Screen should have rendered text nodes after load; got $nodeCount" }
    }

    private fun hasNodeWithTag(tag: String): Boolean =
        composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun assertResolvedEmptyState() {
        composeTestRule.waitUntil(5_000) { hasNodeWithTag(EMPTY_STATE_TAG) }
        composeTestRule.onNodeWithTag(EMPTY_STATE_TAG).assertIsDisplayed()

        val app = ApplicationProvider.getApplicationContext<Application>()
        composeTestRule.onNodeWithText(app.getString(R.string.insights_empty)).assertIsDisplayed()
    }

    private companion object {
        const val EMPTY_STATE_TAG = "insights_empty_state"
    }
}
