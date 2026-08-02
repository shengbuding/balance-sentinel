package com.balancesentinel.app.data.console

import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.ui.console.ConsolePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleOriginPolicyTest {
    @Test
    fun `configured URLs require parseable HTTPS origins`() {
        listOf(
            platform(loginUrl = "not a url"),
            platform(loginUrl = "http://login.example.com/sign-in"),
            platform(dashboardUrl = "https://"),
            platform(dashboardUrl = "http://dashboard.example.com/overview")
        ).forEach { invalidPlatform ->
            assertThrows(IllegalArgumentException::class.java) {
                ConsoleOriginPolicy(invalidPlatform)
            }
        }
    }

    @Test
    fun `origins include exact scheme host and non-default port`() {
        val policy = ConsoleOriginPolicy(
            platform(
                loginUrl = "https://Login.Example.com:8443/sign-in",
                dashboardUrl = "https://dashboard.example.com/overview"
            )
        )

        assertEquals(WebOrigin("https", "login.example.com", 8443), policy.loginOrigin)
        assertEquals(WebOrigin.https("dashboard.example.com"), policy.dashboardOrigin)
        assertEquals(setOf(policy.loginOrigin, policy.dashboardOrigin), policy.allowedOrigins)
    }

    @Test
    fun `navigation allows either exact configured origin`() {
        val policy = ConsoleOriginPolicy(platform())

        assertEquals(
            NavigationDecision.AllowInWebView,
            policy.decideNavigation("https://login.example.com/oauth/callback?code=ok")
        )
        assertEquals(
            NavigationDecision.AllowInWebView,
            policy.decideNavigation("https://dashboard.example.com/billing")
        )
    }

    @Test
    fun `navigation opens other HTTP origins externally without substring bypass`() {
        val policy = ConsoleOriginPolicy(platform())
        val urls = listOf(
            "https://dashboard.example.com.evil.test/overview",
            "https://evil.test/?next=dashboard.example.com",
            "http://external.example.net/help"
        )

        urls.forEach { url ->
            val decision = policy.decideNavigation(url)
            assertTrue(decision is NavigationDecision.OpenExternal)
            assertEquals(url, (decision as NavigationDecision.OpenExternal).uri.toString())
        }
    }

    @Test
    fun `navigation rejects malformed and non HTTP schemes`() {
        val policy = ConsoleOriginPolicy(platform())

        listOf(
            "javascript:alert(1)",
            "intent://dashboard.example.com/#Intent;scheme=https;end",
            "https://",
            "not a url"
        ).forEach { url ->
            assertEquals(NavigationDecision.Reject, policy.decideNavigation(url))
        }
    }

    @Test
    fun `storage injection requires exact dashboard origin`() {
        val policy = ConsoleOriginPolicy(platform())

        assertTrue(policy.canInjectLocalStorage("https://dashboard.example.com/overview"))
        assertFalse(policy.canInjectLocalStorage("https://login.example.com/sign-in"))
        assertFalse(policy.canInjectLocalStorage("https://dashboard.example.com.evil.test/overview"))
        assertFalse(policy.canInjectLocalStorage("https://evil.test/?next=dashboard.example.com"))
        assertFalse(policy.canInjectLocalStorage("not a url"))
    }

    @Test
    fun `identical configured origins are represented once`() {
        val policy = ConsoleOriginPolicy(
            platform(
                loginUrl = "https://console.example.com/sign-in",
                dashboardUrl = "https://console.example.com/overview"
            )
        )

        assertEquals(setOf(WebOrigin.https("console.example.com")), policy.allowedOrigins)
        assertEquals(setOf("https://console.example.com"), policy.webStorageOrigins())
    }

    @Test
    fun `cookie injection target is the configured login URL`() {
        val policy = ConsoleOriginPolicy(platform())

        assertEquals("https://login.example.com/sign-in", policy.cookieInjectionUrl)
    }

    @Test
    fun `API membership uses parsed HTTPS host rather than URL substring`() {
        val policy = ConsoleOriginPolicy(platform())

        assertTrue(policy.isAllowedApiRequest("https://api.deepseek.com/v1/models"))
        assertTrue(policy.isAllowedApiRequest("https://dashboard.example.com/api/usage"))
        assertFalse(policy.isAllowedApiRequest("https://api.deepseek.com.evil.test/v1/models"))
        assertFalse(policy.isAllowedApiRequest("https://evil.test/v1/models?next=api.deepseek.com"))
        assertFalse(policy.isAllowedApiRequest("http://api.deepseek.com/v1/models"))
        assertFalse(policy.isAllowedApiRequest("https://dashboard.example.com/settings"))
    }

    @Test
    fun `navigation handler allows trusted origin dispatches external HTTP and consumes rejects`() {
        val openedUris = mutableListOf<String>()
        val handler = ConsoleNavigationHandler(ConsoleOriginPolicy(platform())) { uri ->
            openedUris += uri.toString()
        }

        assertFalse(handler.shouldOverride("https://dashboard.example.com/overview"))
        assertTrue(handler.shouldOverride("https://external.example.net/help"))
        assertEquals(listOf("https://external.example.net/help"), openedUris)
        assertTrue(handler.shouldOverride("javascript:alert(1)"))
        assertEquals(1, openedUris.size)
    }

    @Test
    fun `cookie injector writes each cookie once to login URL and flushes`() {
        val sink = RecordingCookieSink()
        val injector = ConsoleCookieInjector(ConsoleOriginPolicy(platform()), sink)

        injector.inject(linkedMapOf("session" to "one", "region" to "two"))

        assertEquals(
            listOf(
                "https://login.example.com/sign-in" to "session=one",
                "https://login.example.com/sign-in" to "region=two"
            ),
            sink.writes
        )
        assertEquals(1, sink.flushCalls)
    }

    private fun platform(
        loginUrl: String = "https://login.example.com/sign-in",
        dashboardUrl: String = "https://dashboard.example.com/overview"
    ) = ConsolePlatform(
        id = "custom",
        name = "Custom",
        loginUrl = loginUrl,
        dashboardUrl = dashboardUrl,
        successUrlPatterns = listOf("/overview")
    )

    private class RecordingCookieSink : ConsoleCookieSink {
        val writes = mutableListOf<Pair<String, String>>()
        var flushCalls = 0

        override fun setCookie(url: String, cookie: String) {
            writes += url to cookie
        }

        override fun flush() {
            flushCalls++
        }
    }
}
