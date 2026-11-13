package com.balancesentinel.app.data.console

import android.content.Context
import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.console.ConsolePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleStoreTest {
    private val store = ConsoleStore.createForTesting(inMemorySharedPreferences())

    @Test
    fun `public construction cannot inject unencrypted preferences`() {
        val publicConstructors = ConsoleStore::class.java.constructors
            .filterNot { it.isSynthetic }
            .map { constructor -> constructor.parameterTypes.toList() }

        assertEquals(listOf(listOf(Context::class.java)), publicConstructors)
    }

    @Test
    fun `expired session is deleted on valid read`() {
        store.saveSession(DEEPSEEK, session(lastActiveTime = NOW - THIRTY_DAYS_MS))

        assertNull(store.getValidSession(DEEPSEEK, now = NOW))
        assertNull(store.getSession(DEEPSEEK))
    }

    @Test
    fun `unexpired session is returned without deletion`() {
        val session = session(lastActiveTime = NOW - THIRTY_DAYS_MS + 1)
        store.saveSession(DEEPSEEK, session)

        assertEquals(session, store.getValidSession(DEEPSEEK, now = NOW))
        assertEquals(session, store.getSession(DEEPSEEK))
    }

    @Test
    fun `validity probe deletes expired session`() {
        store.saveSession(DEEPSEEK, session(lastActiveTime = 0L))

        assertFalse(store.hasValidSession(DEEPSEEK))
        assertNull(store.getSession(DEEPSEEK))
    }

    @Test
    fun `editing a stored platform persists new values`() {
        store.addPlatform(platform(name = "中转站", loginUrl = "https://ai.gitee.com/#/login"))

        val updated = store.updatePlatform(
            platform(name = "中转站二号", loginUrl = "https://ai.gitee.com/#/sign_in")
        )

        assertTrue(updated)
        val stored = store.getPlatforms().single()
        assertEquals("中转站二号", stored.name)
        assertEquals("https://ai.gitee.com/#/sign_in", stored.loginUrl)
    }

    @Test
    fun `editing an unknown platform changes nothing`() {
        store.addPlatform(platform())

        val original = store.getPlatforms().single()
        val updated = store.updatePlatform(original.copy(id = "missing", name = "改名"))

        assertFalse(updated)
        assertEquals(listOf(original), store.getPlatforms())
    }

    @Test
    fun `changing the login url drops the stale session`() {
        store.addPlatform(platform(loginUrl = "https://ai.gitee.com/#/login"))
        store.saveSession(PLATFORM_ID, session(lastActiveTime = NOW))

        store.updatePlatform(platform(loginUrl = "https://ai.gitee.com/#/sign_in"))

        assertNull(store.getSession(PLATFORM_ID))
    }

    @Test
    fun `editing other fields keeps the session`() {
        store.addPlatform(platform())
        store.saveSession(PLATFORM_ID, session(lastActiveTime = NOW))

        store.updatePlatform(platform(name = "只改名字"))

        assertNotNull(store.getSession(PLATFORM_ID))
    }

    private fun platform(
        name: String = "中转站",
        loginUrl: String = "https://ai.gitee.com/#/login"
    ) = ConsolePlatform(
        id = PLATFORM_ID,
        name = name,
        loginUrl = loginUrl,
        dashboardUrl = "https://ai.gitee.com/#/console/usage",
        successUrlPatterns = listOf("/#/console/usage")
    )

    private fun session(lastActiveTime: Long) = ConsoleSession(
        cookies = mapOf("session" to "value"),
        loginTime = lastActiveTime,
        lastActiveTime = lastActiveTime
    )

    private companion object {
        const val PLATFORM_ID = "custom_1"
        const val DEEPSEEK = "deepseek"
        const val NOW = 5_000_000_000_000L
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
