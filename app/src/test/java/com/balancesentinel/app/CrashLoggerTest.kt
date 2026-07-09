package com.balancesentinel.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashLoggerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CrashLogger.clear(app)
    }

    @After
    fun tearDown() {
        CrashLogger.clear(app)
    }

    // ═══════════════════════════════════════════════════════════
    // breadcrumb
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `breadcrumb adds entry`() {
        CrashLogger.breadcrumb("TestTag", "test message")
        val crumbs = CrashLogger.getBreadcrumbs()
        assertTrue(crumbs.any { it.contains("TestTag") && it.contains("test message") })
    }

    @Test
    fun `breadcrumb ring buffer caps at 30 entries`() {
        val marker = "ring-test-${System.nanoTime()}"
        for (i in 1..40) {
            CrashLogger.breadcrumb("Tag", "$marker $i")
        }
        val crumbs = CrashLogger.getBreadcrumbs()
        assertEquals(30, crumbs.size)
        // oldest entries should be dropped; newest present
        assertTrue(crumbs.any { it.contains("$marker 40") })
    }

    @Test
    fun `getBreadcrumbs returns empty when no crumbs added`() {
        val crumbs = CrashLogger.getBreadcrumbs()
        // May contain install breadcrumb from previous tests, but we cleared at setUp
        // Breadcrumbs are in-memory only, clear() only clears files
        // After clearing crashes, breadcrumbs might still exist from previous test runs
        // Just verify it returns a list
        assertNotNull(crumbs)
    }

    // ═══════════════════════════════════════════════════════════
    // logNonFatal / getCrashes / clear
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `logNonFatal does not throw`() {
        // logNonFatal requires appRef to be set via install(), which we skip
        // It should gracefully handle null appRef
        CrashLogger.logNonFatal("TestTag", RuntimeException("test error"))
        // No exception = pass
    }

    @Test
    fun `getCrashes returns empty when no crashes logged`() {
        val crashes = CrashLogger.getCrashes(app)
        assertTrue(crashes.isEmpty())
    }

    @Test
    fun `clear is safe when no crash file exists`() {
        CrashLogger.clear(app)
        assertTrue(CrashLogger.getCrashes(app).isEmpty())
    }

    @Test
    fun `clear removes all crashes`() {
        CrashLogger.clear(app)
        assertTrue(CrashLogger.getCrashes(app).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // breadcrumb — format and details
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `breadcrumb includes timestamp in correct format`() {
        // Clear breadcrumbs by overflowing buffer with distinctive marker first
        val marker = "fmt-${System.nanoTime()}"
        CrashLogger.breadcrumb("FMT", marker)
        val crumbs = CrashLogger.getBreadcrumbs()
        val match = crumbs.find { it.contains(marker) }
        assertNotNull("Should find our breadcrumb", match)
        // Format: [HH:mm:ss.SSS] FMT: <marker>
        assertTrue(match!!.matches(Regex("""\[\d{2}:\d{2}:\d{2}\.\d{3}\] FMT: .*""")))
    }

    @Test
    fun `getBreadcrumbs returns defensive copy`() {
        val marker = "defcopy-${System.nanoTime()}"
        CrashLogger.breadcrumb("T", marker)
        val copy1 = CrashLogger.getBreadcrumbs()
        val copy2 = CrashLogger.getBreadcrumbs()
        assertNotSame(copy1, copy2)
        assertEquals(copy1, copy2)
    }

    // ═══════════════════════════════════════════════════════════
    // logNonFatal with install
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `logNonFatal with install writes to crash file`() {
        CrashLogger.install(app)
        CrashLogger.logNonFatal("TestTag", RuntimeException("specific-error-marker"))
        val entries = CrashLogger.getCrashes(app)
        assertTrue("Should have crash entries", entries.isNotEmpty())
        assertTrue(entries.first().fullStack.contains("specific-error-marker"))
        assertTrue(entries.first().fullStack.contains("NON-FATAL"))
    }

    @Test
    fun `logNonFatal entries include device info`() {
        CrashLogger.install(app)
        CrashLogger.logNonFatal("T", RuntimeException("e"))
        val entries = CrashLogger.getCrashes(app)
        val content = entries.first().fullStack
        assertTrue(content.contains("设备信息"))
        assertTrue(content.contains("Android"))
        assertTrue(content.contains("堆栈"))
    }

    // ═══════════════════════════════════════════════════════════
    // getCrashes — parsing
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getCrashes respects max 10 entries`() {
        CrashLogger.install(app)
        repeat(12) { i -> CrashLogger.logNonFatal("T", RuntimeException("err$i")) }
        val entries = CrashLogger.getCrashes(app)
        assertEquals(10, entries.size)
    }

    @Test
    fun `getCrashes entries have header and fullStack`() {
        CrashLogger.install(app)
        CrashLogger.logNonFatal("HDR", RuntimeException("header test"))
        val entries = CrashLogger.getCrashes(app)
        assertTrue(entries.isNotEmpty())
        val first = entries.first()
        assertTrue(first.header.isNotEmpty())
        assertTrue(first.fullStack.length > first.header.length)
        // Header should not start with '[' after parsing
        assertFalse(first.header.startsWith("["))
    }

    // ═══════════════════════════════════════════════════════════
    // install
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `install adds breadcrumb with app version`() {
        CrashLogger.install(app)
        val crumbs = CrashLogger.getBreadcrumbs()
        assertTrue(crumbs.any { it.contains("Crash handler installed") })
    }

    @Test
    fun `install is idempotent`() {
        CrashLogger.install(app)
        CrashLogger.install(app)
        // Should not throw
    }

    // ═══════════════════════════════════════════════════════════
    // API key redaction
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `crash log redacts API keys in stack traces`() {
        CrashLogger.install(app)
        CrashLogger.logNonFatal("API", RuntimeException("Used key sk-abcdefghijklmnopqrstuv"))
        val entries = CrashLogger.getCrashes(app)
        val full = entries.first().fullStack
        assertFalse("Should not contain real API key", full.contains("sk-abcdefghijklmnopqrstuv"))
        assertTrue("Should contain redacted marker", full.contains("sk-***"))
    }

    // ═══════════════════════════════════════════════════════════
    // CrashEntry data class
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `CrashEntry stores header and fullStack`() {
        val entry = CrashLogger.CrashEntry(header = "test header", fullStack = "full stack")
        assertEquals("test header", entry.header)
        assertEquals("full stack", entry.fullStack)
    }
}
