package com.balancesentinel.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.ui.components.AccountBalanceCard
import com.balancesentinel.app.ui.screen.OnboardingScreen
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityWorkflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboardingPrimaryActionHasAccessibleTouchTarget() {
        composeRule.setContent {
            DeepSeekBalanceTheme {
                OnboardingScreen(onComplete = {})
            }
        }

        composeRule.onNodeWithTag("onboarding_primary_action")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun accountActionsUseLocalizedDescriptionsAndFortyEightDpBounds() {
        val description = composeRule.activity.getString(R.string.account_more_actions)
        composeRule.setContent {
            DeepSeekBalanceTheme {
                AccountBalanceCard(
                    accountLabel = "Accessibility account",
                    accountId = "accessibility-account",
                    providerType = ProviderType.DEEPSEEK,
                    balance = null,
                    now = System.currentTimeMillis(),
                    onLongPress = {},
                    onEdit = {},
                    onDelete = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(description)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
