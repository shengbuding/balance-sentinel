package com.balancesentinel.app.ui.console

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.console.ConsoleCookieManager
import com.balancesentinel.app.data.console.ConsoleSessionCleaner
import com.balancesentinel.app.data.console.ConsoleWebStorage
import com.balancesentinel.app.data.console.inMemorySharedPreferences
import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.viewmodel.ConsoleUiState
import com.balancesentinel.app.ui.viewmodel.ConsoleViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConsoleScreenSecurityRegressionTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stored invalid legacy platform opens in non-WebView configuration state`() {
        val store = ConsoleStore(
            context = mockk(relaxed = true),
            injectedPrefs = inMemorySharedPreferences()
        )
        store.addPlatform(INVALID_LEGACY_PLATFORM)
        val restoredPlatform = store.getPlatforms().single()

        assertEquals(
            ConsoleScreenContent.InvalidConfiguration,
            resolveConsoleScreenContent(restoredPlatform, ConsoleUiState())
        )
    }

    @Test
    fun `delayed logout leaves dashboard branch before completion`() {
        val store = ConsoleStore(
            context = application,
            injectedPrefs = inMemorySharedPreferences()
        )
        val cookies = DelayedCookieManager()
        val cleaner = ConsoleSessionCleaner(store, RecordingWebStorage(), cookies)
        store.saveSession(PLATFORM.id, validSession())
        val viewModel = ConsoleViewModel(application, PLATFORM, store, cleaner)
        var navigationCompleted = false
        assertEquals(
            ConsoleScreenContent.Dashboard,
            resolveConsoleScreenContent(PLATFORM, viewModel.uiState.value)
        )

        viewModel.logout { navigationCompleted = true }

        assertEquals(
            ConsoleScreenContent.LogoutProgress,
            resolveConsoleScreenContent(PLATFORM, viewModel.uiState.value)
        )
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertFalse(navigationCompleted)

        cookies.completeRemoval()

        assertEquals(
            ConsoleScreenContent.Login,
            resolveConsoleScreenContent(PLATFORM, viewModel.uiState.value)
        )
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertTrue(navigationCompleted)
    }

    private fun validSession(): ConsoleSession {
        val now = System.currentTimeMillis()
        return ConsoleSession(loginTime = now, lastActiveTime = now)
    }

    private class RecordingWebStorage : ConsoleWebStorage {
        override fun deleteOrigin(origin: String) = Unit
    }

    private class DelayedCookieManager : ConsoleCookieManager {
        private var completion: ((Boolean) -> Unit)? = null

        override fun removeAllCookies(completion: (Boolean) -> Unit) {
            this.completion = completion
        }

        override fun flush() = Unit

        fun completeRemoval() {
            completion?.invoke(true)
            completion = null
        }
    }

    private companion object {
        val PLATFORM = ConsolePlatform(
            id = "deepseek",
            name = "DeepSeek",
            loginUrl = "https://platform.deepseek.com/sign_in",
            dashboardUrl = "https://platform.deepseek.com/overview",
            successUrlPatterns = listOf("/overview")
        )
        val INVALID_LEGACY_PLATFORM = ConsolePlatform(
            id = "legacy",
            name = "Legacy",
            loginUrl = "not a url",
            dashboardUrl = "http://legacy.example.com/dashboard",
            successUrlPatterns = listOf("/dashboard")
        )
    }
}
