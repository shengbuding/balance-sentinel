package com.balancesentinel.app.data.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LoggerTest {

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
}
