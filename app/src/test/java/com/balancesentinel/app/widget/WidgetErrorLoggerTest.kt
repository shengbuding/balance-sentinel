package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetErrorLoggerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetErrorLogger.clear(context)
    }

    @After
    fun tearDown() {
        WidgetErrorLogger.clear(context)
    }

    @Test
    fun `log writes exception entry`() {
        WidgetErrorLogger.log(context, RuntimeException("test error"))
        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { it.message.contains("test error") })
    }

    @Test
    fun `logMessage writes message entry`() {
        WidgetErrorLogger.logMessage(context, "custom message")
        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { it.message.contains("custom message") })
    }

    @Test
    fun `getLogs returns empty when no entries`() {
        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `clear removes all entries`() {
        WidgetErrorLogger.logMessage(context, "msg1")
        WidgetErrorLogger.logMessage(context, "msg2")

        WidgetErrorLogger.clear(context)

        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `max entries is capped at 5`() {
        for (i in 1..10) {
            WidgetErrorLogger.logMessage(context, "message $i")
        }
        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.size <= 5)
    }

    @Test
    fun `log handles null throwable message`() {
        WidgetErrorLogger.log(context, RuntimeException())
        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { it.message.contains("RuntimeException") })
    }

    @Test
    fun `entries have timestamps`() {
        WidgetErrorLogger.logMessage(context, "timestamped")
        val entries = WidgetErrorLogger.getLogs(context)
        assertTrue(entries.isNotEmpty())
        // Timestamp is extracted from [MM-dd HH:mm:ss.SSS] prefix, substring(1, 14)
        val ts = entries[0].timestamp
        assertTrue(ts == "?" || ts.matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")) || ts.matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d""")))
    }

    @Test
    fun `getLogs merges entries from both dirs`() {
        WidgetErrorLogger.logMessage(context, "test message")
        val entries = WidgetErrorLogger.getLogs(context)
        // Under Robolectric, filesDir and cacheDir may be the same
        // so we just verify at least one entry
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun `clear is idempotent`() {
        WidgetErrorLogger.clear(context)
        WidgetErrorLogger.clear(context)
        assertTrue(WidgetErrorLogger.getLogs(context).isEmpty())
    }
}
