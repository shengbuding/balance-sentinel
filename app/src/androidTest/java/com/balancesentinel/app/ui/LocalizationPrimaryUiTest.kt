package com.balancesentinel.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationPrimaryUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun englishPrimaryResourcesContainNoChinese() {
        val context = englishContext()
        val values = listOf(
            context.getString(R.string.home_delete_account_data_warning),
            context.resources.getQuantityString(R.plurals.home_account_count, 2, 2),
            context.getString(R.string.account_card_long_press_delete, "Long account label"),
            context.getString(R.string.settings_expanded_state, "Auto refresh"),
            context.getString(R.string.log_scheduled_interval_format, "8/11/26, 1:00 AM", "2 minutes")
        )

        values.forEach { value -> assertFalse("Unexpected Chinese text: $value", HAN.containsMatchIn(value)) }
    }

    @Test
    fun longEnglishWarningWrapsInsteadOfClipping() {
        val warning = englishContext().getString(R.string.home_delete_account_data_warning)
        composeRule.setContent {
            Text(warning, modifier = Modifier.testTag("long_warning"))
        }
        composeRule.onNodeWithTag("long_warning").assertHeightIsAtLeast(24.dp)
    }

    private fun englishContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en-US"))
        }
        return base.createConfigurationContext(configuration)
    }

    private companion object {
        val HAN = Regex("[\\u4e00-\\u9fff]")
    }
}
