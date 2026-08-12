package com.balancesentinel.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalizedFormatterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `amount and currency honor chinese and english locales`() {
        val chinese = formatter(Locale.SIMPLIFIED_CHINESE)
        val english = formatter(Locale.US)

        assertEquals("1,234.50", chinese.formatAmount("1234.5"))
        assertEquals("1,234.50", english.formatAmount("1234.5"))
        assertTrue(chinese.formatCurrency(12.5, "CNY").contains("12.50"))
        assertTrue(english.formatCurrency(12.5, "USD").contains("$"))
        assertEquals("ZZZ", english.currencySymbol("zzz"))
        assertTrue(english.formatCurrency(12.5, "zzz").startsWith("ZZZ "))
    }

    @Test
    fun `dates and relative time honor locale resources`() {
        val chinese = formatter(Locale.SIMPLIFIED_CHINESE)
        val english = formatter(Locale.US)
        val now = 1_800_000_000_000L

        assertEquals("2分钟前", chinese.formatRelativeTime(now - 120_000L, now))
        assertEquals("1 minute ago", english.formatRelativeTime(now - 60_000L, now))
        assertEquals("2 hours ago", english.formatRelativeTime(now - 7_200_000L, now))
        assertNotEquals(chinese.formatDateTime(now), english.formatDateTime(now))
    }

    @Test
    fun `duration plurals and compact values are locale aware`() {
        val chinese = formatter(Locale.SIMPLIFIED_CHINESE)
        val english = formatter(Locale.US)

        assertEquals("2分30秒", chinese.formatInterval(150))
        assertEquals("1 minute", english.formatInterval(60))
        assertEquals("2 min 30 sec", english.formatInterval(150))
        assertEquals("1.2万", chinese.formatCompactNumber(12_000))
        assertEquals("12K", english.formatCompactNumber(12_000))
    }

    @Test
    fun `invalid numeric input remains visible`() {
        assertEquals("unknown", formatter(Locale.US).formatAmount("unknown"))
    }

    private fun formatter(locale: Locale): LocalizedFormatter {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        val localizedContext = context.createConfigurationContext(configuration)
        return LocalizedFormatter(localizedContext, locale)
    }
}
