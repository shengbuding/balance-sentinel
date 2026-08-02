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
    private val policy: ConsoleOriginPolicy,
    private val externalNavigator: ConsoleExternalNavigator
) {
    fun shouldOverride(url: String): Boolean = when (val decision = policy.decideNavigation(url)) {
        NavigationDecision.AllowInWebView -> false
        is NavigationDecision.OpenExternal -> {
            externalNavigator.open(decision.uri)
            true
        }
        NavigationDecision.Reject -> true
    }
}

interface ConsoleCookieSink {
    fun setCookie(url: String, cookie: String)
    fun flush()
}

class ConsoleCookieInjector(
    private val policy: ConsoleOriginPolicy,
    private val sink: ConsoleCookieSink
) {
    fun inject(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        cookies.forEach { (name, value) ->
            sink.setCookie(policy.cookieInjectionUrl, "$name=$value")
        }
        sink.flush()
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

    companion object {
        fun createOrNull(platform: ConsolePlatform): ConsoleOriginPolicy? {
            return try {
                ConsoleOriginPolicy(platform)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun isValidHttpsUrl(value: String): Boolean {
            val parsed = value.toHttpUrlOrNull()
            return parsed != null && parsed.scheme == "https"
        }

        const val HTTPS_PORT = 443
        private val DEFAULT_API_HOSTS = setOf(
            "platform.deepseek.com",
            "api.deepseek.com",
            "platform.xiaomimimo.com",
            "api.xiaomimimo.com"
        )
        private val API_PATH_MARKERS = listOf("/api/", "/v1/", "/v2/")

        private fun requireHttpsUrl(value: String, fieldName: String): HttpUrl {
            val parsed = value.toHttpUrlOrNull()
            require(isValidHttpsUrl(value)) {
                "$fieldName must be a valid HTTPS URL"
            }
            return requireNotNull(parsed)
        }
    }
}
