package com.balancesentinel.app.data.console

import android.net.Uri
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.ui.console.ConsolePlatform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface NavigationDecision {
    data object AllowInWebView : NavigationDecision
    data class OpenExternal(val uri: Uri) : NavigationDecision
    data object Reject : NavigationDecision
}

fun interface ConsoleExternalNavigator {
    fun open(uri: Uri)
}

class ConsoleNavigationHandler(
    @Suppress("UNUSED_PARAMETER") policy: ConsoleOriginPolicy,
    @Suppress("UNUSED_PARAMETER") externalNavigator: ConsoleExternalNavigator
) {
    fun shouldOverride(url: String): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val ignoredUrl = url
        return true
    }
}

interface ConsoleCookieSink {
    fun setCookie(url: String, cookie: String)
    fun flush()
}

class ConsoleCookieInjector(
    @Suppress("UNUSED_PARAMETER") policy: ConsoleOriginPolicy,
    @Suppress("UNUSED_PARAMETER") sink: ConsoleCookieSink
) {
    fun inject(cookies: Map<String, String>) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredCookies = cookies
    }
}

class ConsoleOriginPolicy(platform: ConsolePlatform) {
    private val loginUrl = requireHttpsUrl(platform.loginUrl, "loginUrl")
    private val dashboardUrl = requireHttpsUrl(platform.dashboardUrl, "dashboardUrl")

    val loginOrigin: WebOrigin = WebOrigin.from(loginUrl)
    val dashboardOrigin: WebOrigin = WebOrigin.from(dashboardUrl)
    val allowedOrigins: Set<WebOrigin> = setOf(loginOrigin, dashboardOrigin)
    val cookieInjectionUrl: String = platform.loginUrl

    private val allowedApiHosts = DEFAULT_API_HOSTS + allowedOrigins.map(WebOrigin::host)

    fun decideNavigation(url: String): NavigationDecision {
        val parsed = url.toHttpUrlOrNull() ?: return NavigationDecision.Reject
        return if (WebOrigin.from(parsed) in allowedOrigins) {
            NavigationDecision.AllowInWebView
        } else {
            NavigationDecision.OpenExternal(Uri.parse(parsed.toString()))
        }
    }

    fun canInjectLocalStorage(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return WebOrigin.from(parsed) == dashboardOrigin
    }

    fun isAllowedApiRequest(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val isApiPath = API_PATH_MARKERS.any(parsed.encodedPath::contains)
        return parsed.scheme == "https" && parsed.host in allowedApiHosts && isApiPath
    }

    fun webStorageOrigins(): Set<String> = allowedOrigins.mapTo(linkedSetOf(), ::canonicalOrigin)

    private fun canonicalOrigin(origin: WebOrigin): String = buildString {
        append(origin.scheme)
        append("://")
        append(origin.host)
        if (origin.port != HTTPS_PORT) {
            append(':')
            append(origin.port)
        }
    }

    private companion object {
        const val HTTPS_PORT = 443
        val DEFAULT_API_HOSTS = setOf(
            "platform.deepseek.com",
            "api.deepseek.com",
            "platform.xiaomimimo.com",
            "api.xiaomimimo.com"
        )
        val API_PATH_MARKERS = listOf("/api/", "/v1/", "/v2/")

        fun requireHttpsUrl(value: String, fieldName: String): HttpUrl {
            val parsed = value.toHttpUrlOrNull()
            require(parsed != null && parsed.scheme == "https") {
                "$fieldName must be a valid HTTPS URL"
            }
            return parsed
        }
    }
}
