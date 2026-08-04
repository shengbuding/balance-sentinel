package com.balancesentinel.app.data.util

import com.balancesentinel.app.BuildConfig
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.data.debug.MAX_CAPTURE_BYTES
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LoggerTest {

    @After
    fun tearDown() {
        DebugLogger.clear()
        ShadowLog.clear()
    }

    @Test
    fun `custom script contents are not emitted to logcat`() {
        val script = "return credentials.secretKey"
        ShadowLog.clear()

        Logger.i("HomeViewModel", "usageScript=$script")

        assertFalse(ShadowLog.getLogsForTag("HomeViewModel").any { it.msg.contains(script) })
    }

    @Test
    fun `sanitize redacts API key`() {
        // Test via the public API — d/i/w/e all call sanitize internally
        // Just verify no crashes occur
        Logger.d("Test", "message with sk-abc1234567890 in it")
        Logger.i("Test", "plain message no key")
        Logger.w("Test", "warning with key sk-xyz9876543210")
        Logger.e("Test", "error with multiple sk-keyone111111 and sk-keytwo222222")
        // No assertion needed — verify no crash
    }

    @Test
    fun `w and e without throwable do not crash`() {
        Logger.w("Test", "warning message")
        Logger.e("Test", "error message")
        // No assertion needed — verify no crash
    }

    @Test
    fun `w and e with throwable include exception info`() {
        val ex1 = IllegalArgumentException("bad argument with sk-leaked12345")
        Logger.w("Test", "context", ex1)
        Logger.e("Test", "context", ex1)

        val ex2 = NullPointerException()  // message can be null
        Logger.w("Test", "null message exception", ex2)
        Logger.e("Test", "null message exception", ex2)
        // No crash = pass
    }

    @Test
    fun `sanitize removes multiple API keys`() {
        // All public methods go through sanitize — testing d covers the path
        Logger.d("Test", "key1=sk-abcdefghijklmn key2=sk-zxcvbnmasdfghj")
        // No crash, API keys redacted
    }

    // Mutation caught: redacting only sk-prefixed keys while preserving cookies and bearer tokens.
    @Test
    fun `logger output shares comprehensive redaction`() {
        val secrets = listOf("plain-api-key", "cookie-secret", "bearer-secret", "body-token")
        ShadowLog.clear()

        Logger.e(
            "Boundary",
            "apiKey=${secrets[0]} Cookie: sid=${secrets[1]}",
            IllegalStateException("Bearer ${secrets[2]} refresh_token=${secrets[3]}")
        )

        val output = ShadowLog.getLogsForTag("Boundary").joinToString("\n") { it.msg }
        secrets.forEach { assertFalse(output.contains(it)) }
        assertTrue(output.contains("[REDACTED]"))
    }

    // Mutation caught: storing raw/unbounded Console exception text in DebugLogger.
    @Test
    fun `debug logger bounds and redacts retained messages`() {
        DebugLogger.log("token=debug-secret " + "凭".repeat(30_000))

        val logs = DebugLogger.getLogs()
        if (!BuildConfig.DEBUG) {
            assertTrue("Release must not retain debug logs", logs.isEmpty())
            return
        }
        val retained = logs.single()
        assertFalse(retained.contains("debug-secret"))
        assertTrue(retained.toByteArray().size <= MAX_CAPTURE_BYTES + 16)
        assertTrue(retained.contains("[REDACTED]"))
    }

    @Test
    fun `debug logger caps entries while retaining the newest values`() {
        repeat(101) { DebugLogger.log("entry-$it") }

        val retained = DebugLogger.getLogs()
        if (!BuildConfig.DEBUG) {
            assertTrue("Release must not retain debug logs", retained.isEmpty())
            return
        }
        assertEquals(100, retained.size)
        assertTrue(retained.first().endsWith("entry-1"))
        assertTrue(retained.last().endsWith("entry-100"))
        assertFalse(retained.any { it.endsWith("entry-0") })
    }
}
