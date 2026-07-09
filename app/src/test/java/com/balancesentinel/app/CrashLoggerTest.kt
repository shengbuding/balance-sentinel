package com.balancesentinel.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashLoggerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext() as Application
        CrashLogger.clear(app)
    }

    @After
    fun tearDown() {
        CrashLogger.clear(app)
    }

    // ── breadcrumb ──

    @Test
    fun `breadcrumb adds entry to list`() {
        val before = CrashLogger.getBreadcrumbs().size
        CrashLogger.breadcrumb("TestTag", "test message")
        val crumbs = CrashLogger.getBreadcrumbs()
        assertEquals(before + 1, crumbs.size)
        assertTrue(crumbs.last().contains("TestTag"))
        assertTrue(crumbs.last().contains("test message"))
    }

    @Test
    fun `getBreadcrumbs returns a list`() {
        // Breadcrumbs are shared singleton state — verify it returns a list
        val crumbs = CrashLogger.getBreadcrumbs()
        assertNotNull(crumbs)
        // After adding one, list should not be empty
        val before = crumbs.size
        CrashLogger.breadcrumb("TestTag", "fresh crumb")
        assertEquals(before + 1, CrashLogger.getBreadcrumbs().size)
    }

    @Test
    fun `breadcrumb respects max size of 30`() {
        // Fill until we overflow, then verify cap
        val before = CrashLogger.getBreadcrumbs().size
        val toAdd = 35
        for (i in 1..toAdd) {
            CrashLogger.breadcrumb("MaxTest", "msg $i")
        }
        val crumbs = CrashLogger.getBreadcrumbs()
        assertEquals(30, crumbs.size)
        // The last entry should be the most recent
        assertTrue(crumbs.last().contains("msg $toAdd"))
        // The entries before our batch should be evicted
        // (if before > 0, the first entries we added also got evicted)
        // Use exact suffix match to avoid "msg 1" matching "msg 10".."msg 19"
        val stillHasMsg1 = crumbs.any { it.endsWith("MaxTest: msg 1") }
        assertFalse("msg 1 should have been evicted (35 added, max 30)", stillHasMsg1)
    }

    @Test
    fun `breadcrumb includes timestamp in HHmmss format`() {
        CrashLogger.breadcrumb("Tag", "hello")
        val crumb = CrashLogger.getBreadcrumbs().last()
        // Should have timestamp prefix like [12:34:56.789]
        assertTrue(crumb.matches(Regex("""\[\d{2}:\d{2}:\d{2}\.\d{3}] Tag: hello""")))
    }

    // ── install ──

    @Test
    fun `install adds a breadcrumb with app version`() {
        CrashLogger.install(app)
        val crumbs = CrashLogger.getBreadcrumbs()
        val last = crumbs.last()
        assertTrue(last.contains("Crash handler installed"))
        assertTrue(last.contains("app version="))
    }

    // ── clear ──

    @Test
    fun `clear removes crash log file`() {
        // install sets up the handler and adds a breadcrumb, but crash file is only written on crash
        val crashFile = File(app.filesDir, "crash.log")
        crashFile.writeText("test crash content")
        assertTrue(crashFile.exists())

        CrashLogger.clear(app)
        assertFalse(crashFile.exists())
    }

    @Test
    fun `clear on non-existent file does not throw`() {
        CrashLogger.clear(app) // should not throw
        CrashLogger.clear(app) // should not throw on second call
    }

    // ── getCrashes ──

    @Test
    fun `getCrashes returns empty when no crash file`() {
        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isEmpty())
    }

    @Test
    fun `getCrashes parses crash entries from file`() {
        val crashFile = File(app.filesDir, "crash.log")
        crashFile.writeText(
            "[2026-07-09 12:00:00] [FATAL] java.lang.RuntimeException: test crash\n" +
            "── 设备信息 ──\n  制造商: test\n" +
            "\n══════════════════════════════════\n" +
            "[2026-07-09 13:00:00] [FATAL] java.lang.NullPointerException: null ptr\n" +
            "── 设备信息 ──\n  制造商: test2\n"
        )

        val crashes = CrashLogger.getCrashes(app)
        assertEquals(2, crashes.size)
        assertTrue(crashes[0].header.contains("RuntimeException"))
        assertTrue(crashes[1].header.contains("NullPointerException"))
    }

    @Test
    fun `getCrashes handles empty file gracefully`() {
        val crashFile = File(app.filesDir, "crash.log")
        crashFile.writeText("")

        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isEmpty())
    }

    // ── logNonFatal ──

    @Test
    fun `logNonFatal writes entry when app is installed`() {
        CrashLogger.install(app)
        CrashLogger.logNonFatal("TestTag", RuntimeException("non-fatal error"))

        val crashes = CrashLogger.getCrashes(app)
        assertEquals(1, crashes.size)
        assertTrue(crashes[0].fullStack.contains("non-fatal error"))
        assertTrue(crashes[0].fullStack.contains("NON-FATAL"))
    }

    @Test
    fun `logNonFatal does not write when app not installed`() {
        // Without calling install(), appRef is null
        CrashLogger.logNonFatal("TestTag", RuntimeException("should not appear"))
        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isEmpty())
    }

    @Test
    fun `logNonFatal writes multiple entries up to max`() {
        CrashLogger.install(app)
        for (i in 1..12) {
            CrashLogger.logNonFatal("Tag", RuntimeException("error $i"))
        }
        val crashes = CrashLogger.getCrashes(app)
        // Max 10 entries
        assertTrue(crashes.size <= 10)
        // Most recent should be first
        assertTrue(crashes[0].header.contains("error 12"))
    }

    // ── buildCrashEntry breadcrumbs > 10 ──

    @Test
    fun `crash entry shows truncated breadcrumbs when more than 10`() {
        CrashLogger.install(app)
        // Add 15 breadcrumbs
        for (i in 1..15) {
            CrashLogger.breadcrumb("CrumbTest", "breadcrumb $i")
        }
        CrashLogger.logNonFatal("Tag", RuntimeException("overflow test"))

        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isNotEmpty())
        val entry = crashes[0].fullStack
        // Should have the "... + N more" indicator
        assertTrue(entry.contains("more"))
    }

    @Test
    fun `crash entry includes breadcrumbs when present`() {
        CrashLogger.install(app)
        CrashLogger.breadcrumb("Context", "before crash")
        CrashLogger.logNonFatal("Tag", RuntimeException("with context"))

        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isNotEmpty())
        val entry = crashes[0].fullStack
        assertTrue(entry.contains("── 面包屑 ──"))
        assertTrue(entry.contains("before crash"))
    }

    // ── getCrashes file-not-found ──

    @Test
    fun `getCrashes with blank entries from separator split`() {
        val crashFile = File(app.filesDir, "crash.log")
        // Just separators with nothing meaningful
        crashFile.writeText("\n══════════════════════════════════\n\n══════════════════════════════════\n")
        val crashes = CrashLogger.getCrashes(app)
        // All blank entries filtered out
        assertTrue(crashes.isEmpty())
    }

    // ── sanitize / redaction ──

    @Test
    fun `crash entry redacts API keys in logNonFatal`() {
        CrashLogger.install(app)
        CrashLogger.logNonFatal("Tag", RuntimeException("Error with token sk-abcdefghij12345 in message"))

        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isNotEmpty())
        val entry = crashes[0].fullStack
        // API key should be redacted
        assertFalse(entry.contains("sk-abcdefghij12345"))
        assertTrue(entry.contains("sk-***"))
    }

    // ── logNonFatal multiple entries cap at 10 ──

    @Test
    fun `logNonFatal caps entries at 10 oldest evicted`() {
        CrashLogger.install(app)
        // Write exactly 10 entries first, then an 11th
        for (i in 1..10) {
            CrashLogger.logNonFatal("Tag", RuntimeException("batch $i"))
        }
        // The first entry should be "batch 10" (most recent)
        val firstBatch = CrashLogger.getCrashes(app)
        assertEquals(10, firstBatch.size)
        assertTrue(firstBatch[0].header.contains("batch 10"))
        assertTrue(firstBatch[9].header.contains("batch 1"))

        // Add one more — batch 1 should be evicted
        CrashLogger.logNonFatal("Tag", RuntimeException("batch 11"))
        val secondBatch = CrashLogger.getCrashes(app)
        assertEquals(10, secondBatch.size)
        assertTrue(secondBatch[0].header.contains("batch 11"))
        // batch 1 should be gone
        val hasBatch1 = secondBatch.any { it.header.contains("batch 1") && !it.header.contains("batch 10") && !it.header.contains("batch 11") }
        assertFalse("batch 1 should be evicted", hasBatch1)
    }
}
