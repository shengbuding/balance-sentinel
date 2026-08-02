package com.balancesentinel.app.data.api.balance

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ScriptNetworkPolicyTest {

    // Mutation caught: allowing localhost through hostname validation and consulting DNS.
    @Test
    fun `localhost is denied before dns even when same origin resolves publicly`() {
        val resolver = FakeResolver("localhost" to listOf("93.184.216.34"))
        val policy = ScriptNetworkPolicy(
            baseUrl = "https://localhost/v1".toHttpUrl(),
            authorizedOrigins = emptySet(),
            resolver = resolver
        )

        val decision = policy.validate("https://localhost/balance".toHttpUrl())

        assertEquals(0, resolver.lookups)
        assertFalse(decision.isAllowed)
    }

    // Mutation caught: accepting HTTP, literals, private DNS, or a different registered port.
    @Test
    fun `policy rejects unsafe destinations and dns rebinding`() {
        val policy = ScriptNetworkPolicy(
            baseUrl = "https://api.example.com:8443/v1".toHttpUrl(),
            authorizedOrigins = setOf(WebOrigin.https("cdn.example.com")),
            resolver = FakeResolver(
                "api.example.com" to listOf("93.184.216.34"),
                "cdn.example.com" to listOf("10.0.0.7")
            )
        )

        assertTrue(policy.validate("https://api.example.com:8443/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("https://api.example.com/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("http://api.example.com:8443/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("https://127.0.0.1/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("https://cdn.example.com/balance".toHttpUrl()).isAllowed)
    }

    // Mutation caught: treating a host-only allowlist entry as authorization for any port or user info.
    @Test
    fun `extra origin requires explicit canonical https port 443 authorization`() {
        val resolver = FakeResolver(
            "api.example.com" to listOf("93.184.216.34"),
            "cdn.example.com" to listOf("93.184.216.35"),
            "other.example.com" to listOf("93.184.216.36")
        )
        val policy = ScriptNetworkPolicy(
            baseUrl = "https://api.example.com/v1".toHttpUrl(),
            authorizedOrigins = setOf(WebOrigin.https("cdn.example.com")),
            resolver = resolver
        )

        assertTrue(policy.validate("https://cdn.example.com/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("https://cdn.example.com:8443/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("https://other.example.com/balance".toHttpUrl()).isAllowed)
        assertFalse(policy.validate("https://user@api.example.com/balance".toHttpUrl()).isAllowed)
        assertEquals(1, resolver.lookups)
    }

    // Mutation caught: accepting one public answer when any answer is private, reserved, or non-unicast.
    @Test
    fun `every resolved address must be global unicast`() {
        val deniedAddresses = listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1"
        )
        val hostAddresses = deniedAddresses.mapIndexed { index, address ->
            "blocked-$index.example.com" to listOf(address)
        }.toTypedArray()
        val resolver = FakeResolver(
            *hostAddresses,
            "mixed.example.com" to listOf("93.184.216.34", "10.0.0.1")
        )
        val authorized = hostAddresses.map { (host) -> WebOrigin.https(host) }.toSet() +
            WebOrigin.https("mixed.example.com")
        val policy = ScriptNetworkPolicy(
            baseUrl = "https://api.example.com".toHttpUrl(),
            authorizedOrigins = authorized,
            resolver = resolver
        )

        hostAddresses.forEach { (host) ->
            assertFalse("accepted $host", policy.validate("https://$host/x".toHttpUrl()).isAllowed)
        }
        assertFalse(policy.validate("https://mixed.example.com/x".toHttpUrl()).isAllowed)
    }

    // Mutation caught: treating an IANA special-purpose IPv6 prefix as ordinary global unicast.
    @Test
    fun `special purpose ipv6 is denied while ordinary global ipv6 is allowed`() {
        val deniedAddresses = listOf(
            "64:ff9b::1",
            "64:ff9b:1::1",
            "100::1",
            "100:0:0:1::1",
            "2001::1",
            "2001:2::1",
            "2001:10::1",
            "2001:20::1",
            "2001:30::1",
            "2001:db8::1",
            "2002::1",
            "2620:4f:8000::1",
            "3fff::1",
            "5f00::1",
            "fc00::1",
            "fe80::1",
            "ff00::1"
        )
        val deniedHosts = deniedAddresses.mapIndexed { index, address ->
            "special-$index.example.com" to listOf(address)
        }
        val globalHost = "global.example.com"
        val resolver = FakeResolver(
            *(deniedHosts + (globalHost to listOf("2606:4700:4700::1111"))).toTypedArray()
        )
        val policy = ScriptNetworkPolicy(
            baseUrl = "https://api.example.com".toHttpUrl(),
            authorizedOrigins = (deniedHosts.map { (host) -> WebOrigin.https(host) } +
                WebOrigin.https(globalHost)).toSet(),
            resolver = resolver
        )

        deniedHosts.zip(deniedAddresses).forEach { (entry, address) ->
            val (host) = entry
            assertFalse("accepted $address", policy.validate("https://$host/x".toHttpUrl()).isAllowed)
        }
        assertTrue(policy.validate("https://$globalHost/x".toHttpUrl()).isAllowed)
    }

    // Mutation caught: skipping IDNA and lowercase canonicalization when origins are constructed.
    @Test
    fun `web origins canonicalize internationalized hosts`() {
        assertEquals(
            WebOrigin("https", "xn--bcher-kva.example", 443),
            WebOrigin.https("B\u00dcCHER.example")
        )
    }

    private class FakeResolver(
        vararg entries: Pair<String, List<String>>
    ) : HostResolver {
        private val addresses = entries.toMap()
        var lookups = 0
            private set

        override fun lookup(host: String): List<InetAddress> {
            lookups++
            return addresses[host].orEmpty().map(InetAddress::getByName)
        }
    }
}
