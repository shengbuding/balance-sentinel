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
    fun expandedWidgetLayoutsKeepLocalizedRefreshTargetAtFortyEightDp() {
        val context = largeEnglishContext()
        val minimumTarget = (48 * context.resources.displayMetrics.density).roundToInt()

        expandedLayoutResources.forEach { layoutRes ->
            val root = LayoutInflater.from(context).inflate(layoutRes, null, false)
            val refresh = root.findViewById<android.view.View>(R.id.widget_refresh_btn)
            val balance = root.findViewById<TextView>(R.id.widget_balance)

            assertTrue("refresh width in layout $layoutRes", refresh.layoutParams.width >= minimumTarget)
            assertTrue("refresh height in layout $layoutRes", refresh.layoutParams.height >= minimumTarget)
            assertEquals(context.getString(R.string.home_refresh), refresh.contentDescription.toString())
            assertTrue("balance text must wrap in layout $layoutRes", balance.maxLines >= 2)
        }
    }

    @Test
    fun compactWidgetLayoutsFitTwoByOneContract() {
        val context = largeEnglishContext()
        val compactTarget = (20 * context.resources.displayMetrics.density).roundToInt()

        compactLayoutResources.forEach { layoutRes ->
            val root = LayoutInflater.from(context).inflate(layoutRes, null, false)
            val refresh = root.findViewById<android.view.View>(R.id.widget_refresh_btn)
            val balance = root.findViewById<TextView>(R.id.widget_balance)

            assertEquals("refresh width in layout $layoutRes", compactTarget, refresh.layoutParams.width)
            assertEquals("refresh height in layout $layoutRes", compactTarget, refresh.layoutParams.height)
            assertEquals(context.getString(R.string.home_refresh), refresh.contentDescription.toString())
            assertEquals("compact balance stays on one line", 1, balance.maxLines)
            assertEquals("compact balance ellipsizes", android.text.TextUtils.TruncateAt.END, balance.ellipsize)
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
        val expandedLayoutResources = listOf(
            R.layout.widget_balance,
            R.layout.widget_balance_dark
        )
        val compactLayoutResources = listOf(
            R.layout.widget_balance_compact,
            R.layout.widget_balance_compact_dark
        )
    }
}
