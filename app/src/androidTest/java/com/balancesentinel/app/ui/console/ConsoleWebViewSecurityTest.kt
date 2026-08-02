package com.balancesentinel.app.ui.console

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.balancesentinel.app.data.console.ConsoleCookieInjector
import com.balancesentinel.app.data.console.ConsoleCookieSink
import com.balancesentinel.app.data.console.ConsoleNavigationHandler
import com.balancesentinel.app.data.console.ConsoleOriginPolicy
import com.balancesentinel.app.data.console.ConsoleSessionCleaner
import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ConsoleWebViewSecurityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ConsoleStore(context)

    @Before
    fun setUp() {
        store.clearAll()
        removeAllCookies()
    }

    @After
    fun tearDown() {
        store.clearAll()
        removeAllCookies()
    }

    @Test
    fun localStorageInjectionRunsOnlyForExactDashboardOrigin() {
        assertEquals("\"trusted\"", loadLocalStorage("https://dashboard.example.com/overview"))
        assertEquals("null", loadLocalStorage("https://dashboard.example.com.evil.test/overview"))
    }

    @Test
    fun externalNavigationDispatchesActionViewWhileTrustedNavigationStaysInWebView() {
        val recordingContext = RecordingContext(context)
        val handler = ConsoleNavigationHandler(POLICY, consoleExternalNavigator(recordingContext))

        assertFalse(handler.shouldOverride("https://dashboard.example.com/billing"))
        assertNull(recordingContext.startedIntent)

        assertTrue(handler.shouldOverride("https://external.example.net/help"))
        assertEquals(Intent.ACTION_VIEW, recordingContext.startedIntent?.action)
        assertEquals("https://external.example.net/help", recordingContext.startedIntent?.dataString)
    }

    @Test
    fun logoutPreventsSelectedSessionRestorationAndPreservesOtherEncryptedSession() {
        store.saveSession(PLATFORM.id, validSession())
        store.saveSession(OTHER_PLATFORM_ID, validSession())
        instrumentation.runOnMainSync {
            ConsoleCookieInjector(POLICY, AndroidCookieSink()).inject(mapOf("session" to "value"))
        }
        assertNotNull(CookieManager.getInstance().getCookie(PLATFORM.loginUrl))
        val completion = CountDownLatch(1)

        instrumentation.runOnMainSync {
            ConsoleSessionCleaner(store).logout(PLATFORM) { completion.countDown() }
        }

        assertTrue(completion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertNull(store.getValidSession(PLATFORM.id))
        assertNotNull(store.getValidSession(OTHER_PLATFORM_ID))
        assertNull(CookieManager.getInstance().getCookie(PLATFORM.loginUrl))
    }

    private fun loadLocalStorage(baseUrl: String): String {
        val result = AtomicReference<String>()
        val completion = CountDownLatch(1)
        val webViewReference = AtomicReference<WebView>()
        instrumentation.runOnMainSync {
            val webView = WebView(context)
            webViewReference.set(webView)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    injectConsoleLocalStorage(view, url, mapOf("sentinel" to "trusted"), POLICY)
                    view.evaluateJavascript("localStorage.getItem('sentinel')") { value ->
                        result.set(value)
                        completion.countDown()
                    }
                }
            }
            webView.loadDataWithBaseURL(baseUrl, "<html><body></body></html>", "text/html", "UTF-8", null)
        }

        assertTrue(completion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { webViewReference.get().destroy() }
        return result.get()
    }

    private fun removeAllCookies() {
        val completion = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                completion.countDown()
            }
        }
        assertTrue(completion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun validSession(): ConsoleSession {
        val now = System.currentTimeMillis()
        return ConsoleSession(loginTime = now, lastActiveTime = now)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun startActivity(intent: Intent) {
            startedIntent = intent
        }
    }

    private class AndroidCookieSink : ConsoleCookieSink {
        override fun setCookie(url: String, cookie: String) {
            CookieManager.getInstance().setCookie(url, cookie)
        }

        override fun flush() {
            CookieManager.getInstance().flush()
        }
    }

    private companion object {
        const val OTHER_PLATFORM_ID = "other"
        const val TIMEOUT_SECONDS = 10L
        val PLATFORM = ConsolePlatform(
            id = "custom",
            name = "Custom",
            loginUrl = "https://login.example.com/sign-in",
            dashboardUrl = "https://dashboard.example.com/overview",
            successUrlPatterns = listOf("/overview")
        )
        val POLICY = ConsoleOriginPolicy(PLATFORM)
    }
}
