package com.balancesentinel.app.data.console

import android.net.Uri
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.ui.console.ConsolePlatform

sealed interface NavigationDecision {
    data object AllowInWebView : NavigationDecision
    data class OpenExternal(val uri: Uri) : NavigationDecision
    data object Reject : NavigationDecision
}

class ConsoleOriginPolicy(platform: ConsolePlatform) {
    @Suppress("UNUSED_PARAMETER")
    private val ignoredPlatform = platform

    val loginOrigin: WebOrigin = WebOrigin.https("invalid.example")
    val dashboardOrigin: WebOrigin = WebOrigin.https("invalid.example")
    val allowedOrigins: Set<WebOrigin> = emptySet()
    val cookieInjectionUrl: String? = null

    fun decideNavigation(url: String): NavigationDecision {
        @Suppress("UNUSED_VARIABLE")
        val ignoredUrl = url
        return NavigationDecision.Reject
    }

    fun canInjectLocalStorage(url: String): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val ignoredUrl = url
        return false
    }

    fun isAllowedApiRequest(url: String): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val ignoredUrl = url
        return false
    }

    fun webStorageOrigins(): Set<String> = emptySet()
}
