package com.balancesentinel.app.data.console

import android.content.SharedPreferences
import com.balancesentinel.app.data.console.auth.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConsoleSessionStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var store: ConsoleSessionStore
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.clear() } returns editor

        store = ConsoleSessionStore(mockk(), injectedPrefs = prefs)
    }

    @Test
    fun `saveSession stores session as JSON`() {
        val session = createTestSession()
        store.saveSession(session)
        verify { editor.putString(match { it.startsWith("session_") }, any()) }
    }

    @Test
    fun `getSession returns null when no session exists`() {
        every { prefs.getString(any(), any()) } returns null
        val result = store.getSession("deepseek")
        assertNull(result)
    }

    @Test
    fun `getSession returns session when valid JSON exists`() {
        val session = createTestSession()
        val jsonStr = json.encodeToString(session)
        every { prefs.getString("session_deepseek", null) } returns jsonStr

        val result = store.getSession("deepseek")

        assertNotNull(result)
        assertEquals("deepseek", result?.providerId)
        assertEquals("test@example.com", result?.email)
    }

    @Test
    fun `getSession returns null when JSON is invalid`() {
        every { prefs.getString(any(), any()) } returns "invalid json"
        val result = store.getSession("deepseek")
        assertNull(result)
    }

    @Test
    fun `isLoggedIn returns false when no session`() {
        every { prefs.getString(any(), any()) } returns null
        val result = store.isLoggedIn("deepseek")
        assertFalse(result)
    }

    @Test
    fun `isLoggedIn returns false when session is expired`() {
        val session = createTestSession(expired = true)
        val jsonStr = json.encodeToString(session)
        every { prefs.getString("session_deepseek", null) } returns jsonStr

        val result = store.isLoggedIn("deepseek")
        assertFalse(result)
    }

    @Test
    fun `isLoggedIn returns true when session is valid`() {
        val session = createTestSession(expired = false)
        val jsonStr = json.encodeToString(session)
        every { prefs.getString("session_deepseek", null) } returns jsonStr

        val result = store.isLoggedIn("deepseek")
        assertTrue(result)
    }

    @Test
    fun `clearSession removes session for provider`() {
        store.clearSession("deepseek")
        verify { editor.remove("session_deepseek") }
    }

    @Test
    fun `clearAll clears all data`() {
        store.clearAll()
        verify { editor.clear() }
    }

    private fun createTestSession(expired: Boolean = false): ConsoleSession {
        return ConsoleSession(
            providerId = "deepseek",
            cookies = mapOf("token" to "test_token"),
            token = "test_token",
            email = "test@example.com",
            loginTime = System.currentTimeMillis(),
            expireTime = if (expired) System.currentTimeMillis() - 1000
            else System.currentTimeMillis() + 7 * 24 * 3600 * 1000L,
            lastRefreshTime = System.currentTimeMillis()
        )
    }
}
