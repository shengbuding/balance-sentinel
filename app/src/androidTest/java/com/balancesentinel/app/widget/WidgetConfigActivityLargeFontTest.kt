package com.balancesentinel.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.balancesentinel.app.R
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class WidgetConfigActivityLargeFontTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun realActivityKeepsConfigurationEntriesReachableAtTwoHundredPercentFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val widgetId = 31_032
        val originalFontScale = shell("settings get system font_scale").trim()
        var scenario: ActivityScenario<WidgetConfigActivity>? = null

        try {
            WidgetConfigStore.clearAll(context)
            WalletDatabaseProvider.installForTests(database)
            shell("settings put system font_scale 2.0")
            waitForSystemFontScale(2f)

            val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            scenario = ActivityScenario.launch(intent)
            scenario.onActivity { activity ->
                assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.widget_config_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.widget_config_title))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.widget_config_account))
                .performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.widget_config_currency))
                .performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.home_cancel))
                .performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.home_save))
                .performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithText(context.getString(R.string.home_cancel)).performClick()
            composeRule.waitForIdle()
            assertNull("cancelling must not create a widget configuration", WidgetConfigStore.getConfig(context, widgetId))
        } finally {
            scenario?.close()
            WalletDatabaseProvider.clearForTests()
            WidgetConfigStore.clearAll(context)
            restoreFontScale(originalFontScale)
        }
    }

    private fun restoreFontScale(original: String) {
        val originalValue = original.toFloatOrNull()
        if (originalValue == null) {
            shell("settings delete system font_scale")
            waitForSystemFontScale(1f)
        } else {
            shell("settings put system font_scale $originalValue")
            waitForSystemFontScale(originalValue)
        }
    }

    private fun waitForSystemFontScale(expected: Float) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (abs(shell("settings get system font_scale").trim().toFloatOrNull().orEmpty() - expected) < 0.01f) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            Thread.sleep(50)
        }
        error("system font scale did not become $expected")
    }

    private fun Float?.orEmpty(): Float = this ?: Float.NaN

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
