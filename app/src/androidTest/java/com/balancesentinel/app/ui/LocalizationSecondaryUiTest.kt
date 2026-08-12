package com.balancesentinel.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationSecondaryUiTest {
    @Test
    fun englishConsoleUpdateAndWidgetResourcesContainNoChinese() {
        val context = englishContext()
        val values = listOf(
            context.getString(R.string.console_empty_description),
            context.getString(R.string.console_delete_platform_message, "Example"),
            context.getString(R.string.console_clear_session_details),
            context.getString(R.string.update_download_failed, "offline"),
            context.getString(R.string.widget_query_balance),
            context.getString(R.string.widget_config_total_account)
        )

        values.forEach { value -> assertFalse("Unexpected Chinese text: $value", HAN.containsMatchIn(value)) }
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
