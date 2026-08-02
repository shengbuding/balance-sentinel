package com.balancesentinel.app.data.console

import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.console.ConsolePlatform
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSessionCleanerTest {
    private val store = ConsoleStore(
        context = mockk(relaxed = true),
        injectedPrefs = inMemorySharedPreferences()
    )
    private val webStorage = RecordingWebStorage()
    private val cookies = RecordingCookieManager()
    private val cleaner = ConsoleSessionCleaner(store, webStorage, cookies)

    @Test
    fun `logout removes selected session distinct origins and all runtime cookies`() {
        store.saveSession(PLATFORM.id, session())
        store.saveSession(OTHER_PLATFORM_ID, session())

        cleaner.logout(PLATFORM)

        assertNull(store.getSession(PLATFORM.id))
        assertNotNull(store.getSession(OTHER_PLATFORM_ID))
        assertEquals(
            setOf("https://login.example.com", "https://dashboard.example.com"),
            webStorage.deletedOrigins.toSet()
        )
        assertEquals(1, cookies.removeAllCalls)
        assertEquals(1, cookies.flushCalls)
    }

    @Test
    fun `logout deletes an identical login and dashboard origin once`() {
        val platform = PLATFORM.copy(
            loginUrl = "https://console.example.com/sign-in",
            dashboardUrl = "https://console.example.com/overview"
        )

        cleaner.logout(platform)

        assertEquals(listOf("https://console.example.com"), webStorage.deletedOrigins)
    }

    @Test
    fun `logout flushes and completes only after cookie removal callback`() {
        cookies.completeImmediately = false
        var completed = false

        cleaner.logout(PLATFORM) { completed = true }

        assertEquals(1, cookies.removeAllCalls)
        assertEquals(0, cookies.flushCalls)
        assertFalse(completed)

        cookies.completeRemoval()

        assertEquals(1, cookies.flushCalls)
        assertTrue(completed)
    }

    private fun session() = ConsoleSession(
        cookies = mapOf("session" to "value"),
        loginTime = 1L,
        lastActiveTime = 1L
    )

    private class RecordingWebStorage : ConsoleWebStorage {
        val deletedOrigins = mutableListOf<String>()

        override fun deleteOrigin(origin: String) {
            deletedOrigins += origin
        }
    }

    private class RecordingCookieManager : ConsoleCookieManager {
        var removeAllCalls = 0
        var flushCalls = 0
        var completeImmediately = true
        private var completion: ((Boolean) -> Unit)? = null

        override fun removeAllCookies(completion: (Boolean) -> Unit) {
            removeAllCalls++
            this.completion = completion
            if (completeImmediately) completeRemoval()
        }

        override fun flush() {
            flushCalls++
        }

        fun completeRemoval() {
            completion?.invoke(true)
            completion = null
        }
    }

    private companion object {
        const val OTHER_PLATFORM_ID = "other"
        val PLATFORM = ConsolePlatform(
            id = "deepseek",
            name = "DeepSeek",
            loginUrl = "https://login.example.com/sign-in",
            dashboardUrl = "https://dashboard.example.com/overview",
            successUrlPatterns = listOf("/overview")
        )
    }
}
