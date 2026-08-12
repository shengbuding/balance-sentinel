package com.balancesentinel.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.LayoutInflater
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class WidgetAccessibilityTest {
    @Test
    fun allWidgetLayoutsKeepLocalizedRefreshTargetAtFortyEightDp() {
        val context = largeEnglishContext()
        val minimumTarget = (48 * context.resources.displayMetrics.density).roundToInt()

        layoutResources.forEach { layoutRes ->
            val root = LayoutInflater.from(context).inflate(layoutRes, null, false)
            val refresh = root.findViewById<android.view.View>(R.id.widget_refresh_btn)
            val balance = root.findViewById<TextView>(R.id.widget_balance)

            assertTrue("refresh width in layout $layoutRes", refresh.layoutParams.width >= minimumTarget)
            assertTrue("refresh height in layout $layoutRes", refresh.layoutParams.height >= minimumTarget)
            assertEquals(context.getString(R.string.home_refresh), refresh.contentDescription.toString())
            assertTrue("balance text must wrap in layout $layoutRes", balance.maxLines >= 2)
        }
    }

    private fun largeEnglishContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            fontScale = 2f
            setLocales(LocaleList.forLanguageTags("en-US"))
        }
        return base.createConfigurationContext(configuration)
    }

    private companion object {
        val layoutResources = listOf(
            R.layout.widget_balance,
            R.layout.widget_balance_dark,
            R.layout.widget_balance_compact,
            R.layout.widget_balance_compact_dark
        )
    }
}
