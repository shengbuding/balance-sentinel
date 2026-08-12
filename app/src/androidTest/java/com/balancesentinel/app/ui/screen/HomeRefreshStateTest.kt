package com.balancesentinel.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.ui.components.AccountBalanceCard
import com.balancesentinel.app.ui.viewmodel.AccountRefreshUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRefreshStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loading_is_scoped_to_the_target_account_card() {
        composeTestRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                card("A", AccountRefreshUiState(isLoading = true))
                card("B", AccountRefreshUiState())
            }
        }

        composeTestRule.onAllNodesWithText(string(R.string.loading)).assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription(
            string(R.string.account_card_long_press_delete, "B")
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stale_error_is_scoped_to_the_target_account_card() {
        composeTestRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                card(
                    "A",
                    AccountRefreshUiState(
                        dataTimestamp = 1L,
                        stale = true,
                        errorMessage = "A failed"
                    )
                )
                card("B", AccountRefreshUiState())
            }
        }

        composeTestRule.onNodeWithText("A failed").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("A failed").assertCountEquals(1)
    }

    @Composable
    private fun card(accountLabel: String, refreshState: AccountRefreshUiState) {
        AccountBalanceCard(
            accountLabel = accountLabel,
            accountId = accountLabel,
            providerType = ProviderType.DEEPSEEK,
            balance = BalanceResponse(
                isAvailable = true,
                balanceInfos = listOf(
                    BalanceInfo("USD", "100.0", "0.0", "100.0")
                )
            ),
            now = System.currentTimeMillis(),
            onLongPress = {},
            onEdit = {},
            onDelete = {},
            refreshState = refreshState
        )
    }

    private fun string(id: Int, vararg args: Any): String =
        androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
            .getString(id, *args)
}
