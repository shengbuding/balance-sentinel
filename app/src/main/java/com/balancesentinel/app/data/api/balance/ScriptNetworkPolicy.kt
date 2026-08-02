package com.balancesentinel.app.data.api.balance

import okhttp3.HttpUrl
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

data class WebOrigin(
    val scheme: String,
    val host: String,
    val port: Int
) {
    companion object {
        fun from(url: HttpUrl): WebOrigin = WebOrigin(
            scheme = url.scheme.lowercase(Locale.US),
            host = canonicalHost(url.host),
            port = url.port
        )

        fun https(host: String): WebOrigin = WebOrigin(
            scheme = "https",
            host = canonicalHost(host),
            port = 443
        )

        private fun canonicalHost(host: String): String =
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
    }
}

fun interface HostResolver {
    fun lookup(host: String): List<InetAddress>
}

data class ScriptPolicyDecision(
    val isAllowed: Boolean,
    val reason: String? = null
)

internal data class ResolvedScriptDestination(
    val decision: ScriptPolicyDecision,
    val addresses: List<InetAddress> = emptyList()
)

class ScriptNetworkPolicy(
    baseUrl: HttpUrl,
    authorizedOrigins: Set<WebOrigin>,
    private val resolver: HostResolver
) {
    private val baseOrigin = WebOrigin.from(baseUrl)
    private val authorizedOrigins = authorizedOrigins.toSet()

    fun validate(url: HttpUrl): ScriptPolicyDecision = resolve(url).decision

    internal fun resolve(url: HttpUrl): ResolvedScriptDestination {
        if (url.scheme != "https") {
            return denied("Script requests require HTTPS")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return denied("Script request user information is not allowed")
        }
        if (!isCanonicalDomainName(url.host)) {
            return denied("Script request host is not a canonical domain name")
        }

        val origin = runCatching { WebOrigin.from(url) }.getOrNull()
            ?: return denied("Script request origin is invalid")
        val sameOrigin = origin == baseOrigin
        val authorizedExtraOrigin = origin.scheme == "https" &&
            origin.port == 443 &&
            origin in authorizedOrigins
        if (!sameOrigin && !authorizedExtraOrigin) {
            return denied("Script request origin is not authorized")
        }

        val addresses = try {
            resolver.lookup(origin.host)
        } catch (_: Exception) {
            return denied("Script request host could not be resolved")
        }
        if (addresses.isEmpty() || addresses.any { !it.isGlobalUnicast() }) {
            return denied("Script request host did not resolve to global unicast")
        }
        return ResolvedScriptDestination(
            decision = ScriptPolicyDecision(isAllowed = true),
            addresses = addresses.toList()
        )
    }

    private fun denied(reason: String) = ResolvedScriptDestination(
        ScriptPolicyDecision(isAllowed = false, reason = reason)
    )

    private fun isCanonicalDomainName(host: String): Boolean {
        if (host.isEmpty() || host.endsWith('.') || host.contains(':')) return false
        if (host == "localhost") return false
        if (host.all { it.isDigit() || it == '.' }) return false
        if (host.startsWith("0x", ignoreCase = true)) return false

        val canonical = runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
        }.getOrNull() ?: return false
        if (canonical != host || canonical.length > 253) return false
        return canonical.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}

private fun InetAddress.isGlobalUnicast(): Boolean = when (this) {
    is Inet4Address -> isGlobalIpv4(address)
    is Inet6Address -> isGlobalIpv6(address)
    else -> false
}

private fun isGlobalIpv4(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val third = bytes[2].toInt() and 0xff
    return when {
        first == 0 -> false
        first == 10 -> false
        first == 100 && second in 64..127 -> false
        first == 127 -> false
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 0 && third in setOf(0, 2) -> false
        first == 192 && second == 168 -> false
        first == 198 && second in 18..19 -> false
        first == 198 && second == 51 && third == 100 -> false
        first == 203 && second == 0 && third == 113 -> false
        first >= 224 -> false
        else -> true
    }
}

private fun isGlobalIpv6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    return first in 0x20..0x3f && IPV6_SPECIAL_PURPOSE_PREFIXES.none { bytes.matches(it) }
}

private fun ByteArray.matches(prefix: Ipv6Prefix): Boolean {
    val completeBytes = prefix.bitCount / 8
    for (index in 0 until completeBytes) {
        if (this[index] != prefix.bytes[index]) return false
    }
    val remainingBits = prefix.bitCount % 8
    if (remainingBits == 0) return true
    val mask = (0xff shl (8 - remainingBits)) and 0xff
    return (this[completeBytes].toInt() and mask) ==
        (prefix.bytes[completeBytes].toInt() and mask)
}

private data class Ipv6Prefix(
    val bytes: ByteArray,
    val bitCount: Int
)

private val IPV6_SPECIAL_PURPOSE_PREFIXES = listOf(
    Ipv6Prefix(byteArrayOf(0x20, 0x01, 0x00), 23),
    Ipv6Prefix(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()), 32),
    Ipv6Prefix(byteArrayOf(0x20, 0x02), 16),
    Ipv6Prefix(byteArrayOf(0x26, 0x20, 0x00, 0x4f, 0x80.toByte(), 0x00), 48),
    Ipv6Prefix(byteArrayOf(0x3f, 0xff.toByte(), 0x00), 20)
)
