package com.balancesentinel.app.data.api.balance

import okhttp3.HttpUrl
import java.net.InetAddress

data class WebOrigin(
    val scheme: String,
    val host: String,
    val port: Int
) {
    companion object {
        fun from(url: HttpUrl): WebOrigin = WebOrigin(url.scheme, url.host, url.port)

        fun https(host: String): WebOrigin = WebOrigin("https", host, 443)
    }
}

fun interface HostResolver {
    fun lookup(host: String): List<InetAddress>
}

data class ScriptPolicyDecision(
    val isAllowed: Boolean,
    val reason: String? = null
)

class ScriptNetworkPolicy(
    @Suppress("UNUSED_PARAMETER") baseUrl: HttpUrl,
    @Suppress("UNUSED_PARAMETER") authorizedOrigins: Set<WebOrigin>,
    @Suppress("UNUSED_PARAMETER") resolver: HostResolver
) {
    fun validate(@Suppress("UNUSED_PARAMETER") url: HttpUrl): ScriptPolicyDecision =
        ScriptPolicyDecision(isAllowed = true)
}
