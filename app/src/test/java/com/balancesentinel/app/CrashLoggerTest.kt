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
}
