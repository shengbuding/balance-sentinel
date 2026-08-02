package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.console.ConsoleCookieManager
import com.balancesentinel.app.data.console.ConsoleSessionCleaner
import com.balancesentinel.app.data.console.ConsoleWebStorage
import com.balancesentinel.app.data.console.inMemorySharedPreferences
import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.console.ConsolePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConsoleViewModelTest {
    private lateinit var application: Application
    private lateinit var store: ConsoleStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
        store = ConsoleStore.createForTesting(inMemorySharedPreferences())
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `initialization deletes expired session and remains logged out`() {
        store.saveSession(PLATFORM.id, expiredSession())

        val viewModel = ConsoleViewModel(application, PLATFORM, store)

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.session)
        assertNull(store.getSession(PLATFORM.id))
    }

    @Test
    fun `refresh deletes a newly expired session and logs out`() {
        store.saveSession(PLATFORM.id, validSession())
        val viewModel = ConsoleViewModel(application, PLATFORM, store)
        assertTrue(viewModel.uiState.value.isLoggedIn)

        store.saveSession(PLATFORM.id, expiredSession())
        viewModel.refresh()

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.session)
        assertNull(store.getSession(PLATFORM.id))
    }

    @Test
    fun `logout delegates complete cleanup and preserves other encrypted sessions`() {
        val webStorage = RecordingWebStorage()
        val cookies = RecordingCookieManager()
        val cleaner = ConsoleSessionCleaner(store, webStorage, cookies)
        store.saveSession(PLATFORM.id, validSession())
        store.saveSession(OTHER_PLATFORM_ID, validSession())
        val viewModel = ConsoleViewModel(application, PLATFORM, store, cleaner)
        var completed = false

        viewModel.logout { completed = true }

        assertNull(store.getSession(PLATFORM.id))
        assertNotNull(store.getSession(OTHER_PLATFORM_ID))
        assertEquals(
            setOf("https://platform.deepseek.com"),
            webStorage.deletedOrigins.toSet()
        )
        assertEquals(1, cookies.removeAllCalls)
        assertEquals(1, cookies.flushCalls)
        assertTrue(completed)
        assertFalse(viewModel.uiState.value.isLoggedIn)
    }

    private fun validSession(): ConsoleSession {
        val now = System.currentTimeMillis()
        return ConsoleSession(loginTime = now, lastActiveTime = now)
    }

    private fun expiredSession(): ConsoleSession {
        val timestamp = System.currentTimeMillis() - THIRTY_DAYS_MS
        return ConsoleSession(loginTime = timestamp, lastActiveTime = timestamp)
    }

    private class RecordingWebStorage : ConsoleWebStorage {
        val deletedOrigins = mutableListOf<String>()

        override fun deleteOrigin(origin: String) {
            deletedOrigins += origin
        }
    }

    private class RecordingCookieManager : ConsoleCookieManager {
        var removeAllCalls = 0
        var flushCalls = 0

        override fun removeAllCookies(completion: (Boolean) -> Unit) {
            removeAllCalls++
            completion(true)
        }

        override fun flush() {
            flushCalls++
        }
    }

    private companion object {
        const val OTHER_PLATFORM_ID = "other"
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        val PLATFORM = ConsolePlatform(
            id = "deepseek",
            name = "DeepSeek",
            loginUrl = "https://platform.deepseek.com/sign_in",
            dashboardUrl = "https://platform.deepseek.com/overview",
            successUrlPatterns = listOf("/overview")
        )
    }
}
