package com.balancesentinel.app.data.console

import com.balancesentinel.app.data.console.store.ConsoleSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSessionTest {
    @Test
    fun `session remains valid one millisecond before thirty day boundary`() {
        val session = session(lastActiveTime = NOW - THIRTY_DAYS_MS + 1)

        assertTrue(session.isValid(now = NOW))
    }

    @Test
    fun `session expires exactly at thirty day boundary`() {
        val session = session(lastActiveTime = NOW - THIRTY_DAYS_MS)

        assertFalse(session.isValid(now = NOW))
    }

    @Test
    fun `remembering a visit stores the page and refreshes the active time`() {
        val session = session(lastActiveTime = NOW - THIRTY_DAYS_MS)

        val remembered = session.rememberVisit("https://platform.deepseek.com/usage", now = NOW)

        assertEquals("https://platform.deepseek.com/usage", remembered.lastVisitedUrl)
        assertEquals(NOW, remembered.lastActiveTime)
        assertTrue(remembered.isValid(now = NOW))
    }

    @Test
    fun `a legacy session without a remembered page still deserializes`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"cookies":{"session":"value"},"loginTime":1,"lastActiveTime":1}"""

        val decoded = json.decodeFromString<ConsoleSession>(legacy)

        assertNull(decoded.lastVisitedUrl)
    }

    private fun session(lastActiveTime: Long) = ConsoleSession(
        cookies = mapOf("session" to "value"),
        loginTime = lastActiveTime,
        lastActiveTime = lastActiveTime
    )

    private companion object {
        const val NOW = 5_000_000_000_000L
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
