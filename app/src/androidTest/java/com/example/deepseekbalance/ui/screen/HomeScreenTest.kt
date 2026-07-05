package com.example.deepseekbalance.ui.screen

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `compose rule is available`() {
        // Verify the Compose UI test infrastructure is set up correctly.
        // Full screen tests require a device/emulator and ViewModel injection.
        // Run with: ./gradlew connectedAndroidTest
        composeTestRule.setContent {
            Text("Hello Test")
        }
        composeTestRule.onNodeWithText("Hello Test").assertExists()
    }
}
