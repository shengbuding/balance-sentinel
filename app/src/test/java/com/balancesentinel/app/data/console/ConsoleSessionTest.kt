package com.balancesentinel.app.data.console

import com.balancesentinel.app.data.console.store.ConsoleSession
import org.junit.Assert.*
import org.junit.Test

class ConsoleSessionTest {

    @Test
    fun `session is always valid`() {
        val session = ConsoleSession(
            cookies = mapOf("session" to "value")
        )

        assertTrue(session.isValid())
    }

    @Test
    fun `session with empty cookies is valid`() {
        val session = ConsoleSession(
            cookies = emptyMap()
        )

        assertTrue(session.isValid())
    }

    @Test
    fun `updateActiveTime updates last active time`() {
        val session = ConsoleSession(
            cookies = mapOf("session" to "value")
        )

        val updated = session.updateActiveTime()

        assertTrue(updated.lastActiveTime >= session.lastActiveTime)
    }

    @Test
    fun `session has default values`() {
        val session = ConsoleSession(
            cookies = mapOf("session" to "value")
        )

        assertNotNull(session.loginTime)
        assertNotNull(session.lastActiveTime)
        assertNull(session.token)
        assertNull(session.email)
        assertTrue(session.localStorage.isEmpty())
    }

    @Test
    fun `session with all fields`() {
        val session = ConsoleSession(
            cookies = mapOf("session" to "value"),
            localStorage = mapOf("key" to "value"),
            token = "test_token",
            email = "test@example.com"
        )

        assertTrue(session.isValid())
        assertEquals("test_token", session.token)
        assertEquals("test@example.com", session.email)
        assertEquals(1, session.cookies.size)
        assertEquals(1, session.localStorage.size)
    }
}
