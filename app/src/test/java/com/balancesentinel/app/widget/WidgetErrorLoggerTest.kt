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
    fun `logMessage writes to log`() {
        WidgetErrorLogger.logMessage(context, "test message")
        val logs = WidgetErrorLogger.getLogs(context)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.message.contains("test message") })
    }

    @Test
    fun `log writes exception with stack trace`() {
        WidgetErrorLogger.log(context, RuntimeException("boom"))
        val logs = WidgetErrorLogger.getLogs(context)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.message.contains("RuntimeException") })
        assertTrue(logs.any { it.message.contains("boom") })
    }

    @Test
    fun `log truncates entries longer than 8000 chars`() {
        val longMessage = "A".repeat(9000)
        WidgetErrorLogger.logMessage(context, longMessage)
        val logs = WidgetErrorLogger.getLogs(context)
        val entry = logs.firstOrNull { it.message.contains("[truncated]") }
        assertNotNull("Long message should be truncated", entry)
    }

    @Test
    fun `getLogs returns empty when no logs written`() {
        val logs = WidgetErrorLogger.getLogs(context)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `clear removes all logs`() {
        WidgetErrorLogger.logMessage(context, "msg1")
        WidgetErrorLogger.logMessage(context, "msg2")
        assertTrue(WidgetErrorLogger.getLogs(context).isNotEmpty())

        WidgetErrorLogger.clear(context)
        assertTrue(WidgetErrorLogger.getLogs(context).isEmpty())
    }

    @Test
    fun `keeps at most 5 entries`() {
        for (i in 1..10) {
            WidgetErrorLogger.logMessage(context, "msg-$i")
        }
        val logs = WidgetErrorLogger.getLogs(context)
        // ring buffer caps at 5 entries
        assertTrue("Expected <= 5 but got ${logs.size}", logs.size <= 5)
        // newest entries should be present
        assertTrue(logs.any { it.message.contains("msg-10") })
    }
}
