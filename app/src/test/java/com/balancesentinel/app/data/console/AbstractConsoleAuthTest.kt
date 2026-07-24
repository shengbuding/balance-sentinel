package com.balancesentinel.app.data.console

import com.balancesentinel.app.data.console.auth.AbstractConsoleAuth
import com.balancesentinel.app.data.console.auth.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleSessionStore
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AbstractConsoleAuthTest {

    private lateinit var sessionStore: ConsoleSessionStore
    private lateinit var auth: TestConsoleAuth

    @Before
    fun setUp() {
        sessionStore = mockk(relaxed = true)
        auth = TestConsoleAuth(sessionStore)
    }

    @Test
    fun `isLoggedIn returns false when no session`() = runTest {
        every { sessionStore.isLoggedIn("test") } returns false
        val result = auth.isLoggedIn()
        assertFalse(result)
    }

    @Test
    fun `isLoggedIn returns true when session exists`() = runTest {
        every { sessionStore.isLoggedIn("test") } returns true
        val result = auth.isLoggedIn()
        assertTrue(result)
    }

    @Test
    fun `getSession returns null when session not found`() = runTest {
        every { sessionStore.getSession("test") } returns null
        val result = auth.getSession()
        assertNull(result)
    }

    @Test
    fun `getSession returns null when session is expired`() = runTest {
        val session = createTestSession(expired = true)
        every { sessionStore.getSession("test") } returns session
        val result = auth.getSession()
        assertNull(result)
    }

    @Test
    fun `getSession returns session when valid`() = runTest {
        val session = createTestSession()
        every { sessionStore.getSession("test") } returns session
        val result = auth.getSession()
        assertNotNull(result)
        assertEquals("test", result?.providerId)
    }

    @Test
    fun `logout clears session`() = runTest {
        auth.logout()
        verify { sessionStore.clearSession("test") }
    }

    @Test
    fun `getMaskedEmail returns null when no session`() {
        every { sessionStore.getSession("test") } returns null
        val result = auth.getMaskedEmail()
        assertNull(result)
    }

    @Test
    fun `getMaskedEmail returns masked email`() {
        val session = createTestSession(email = "user@example.com")
        every { sessionStore.getSession("test") } returns session
        val result = auth.getMaskedEmail()
        assertEquals("u***@example.com", result)
    }

    @Test
    fun `getMaskedEmail returns null when email is blank`() {
        val session = createTestSession(email = "")
        every { sessionStore.getSession("test") } returns session
        val result = auth.getMaskedEmail()
        assertNull(result)
    }

    @Test
    fun `maskEmail handles various formats`() {
        assertEquals("u***@example.com", auth.testMaskEmail("user@example.com"))
        assertEquals("a***@test.cn", auth.testMaskEmail("abc@test.cn"))
        assertNull(auth.testMaskEmail(null))
        assertNull(auth.testMaskEmail(""))
    }

    private fun createTestSession(
        expired: Boolean = false,
        cookies: Map<String, String> = mapOf("token" to "test"),
        email: String? = "test@example.com"
    ): ConsoleSession {
        return ConsoleSession(
            providerId = "test",
            cookies = cookies,
            token = "test_token",
            email = email,
            loginTime = System.currentTimeMillis(),
            expireTime = if (expired) System.currentTimeMillis() - 1000
            else System.currentTimeMillis() + 48 * 3600 * 1000L,
            lastRefreshTime = System.currentTimeMillis()
        )
    }

    private class TestConsoleAuth(
        sessionStore: ConsoleSessionStore
    ) : AbstractConsoleAuth(sessionStore, "TestAuth") {
        override val providerId = "test"
        override val displayName = "Test"
        override suspend fun saveSession(cookies: Map<String, String>, email: String?) {}
        fun testMaskEmail(email: String?) = maskEmail(email)
    }
}
