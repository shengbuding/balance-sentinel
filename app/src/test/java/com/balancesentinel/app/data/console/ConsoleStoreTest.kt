package com.balancesentinel.app.data.console

import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ConsoleStoreTest {
    private val store = ConsoleStore(
        context = mockk(relaxed = true),
        injectedPrefs = inMemorySharedPreferences()
    )

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

    private fun session(lastActiveTime: Long) = ConsoleSession(
        cookies = mapOf("session" to "value"),
        loginTime = lastActiveTime,
        lastActiveTime = lastActiveTime
    )

    private companion object {
        const val DEEPSEEK = "deepseek"
        const val NOW = 5_000_000_000_000L
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
