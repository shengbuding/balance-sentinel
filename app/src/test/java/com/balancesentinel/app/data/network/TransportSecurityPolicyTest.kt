package com.balancesentinel.app.data.network

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSecurityPolicyTest {

    @After
    fun tearDown() {
        TransportSecurityPolicy.reset()
    }

    @Test
    fun `https is required by default`() {
        assertTrue(TransportSecurityPolicy.httpsRequired)
        assertTrue(TransportSecurityPolicy.certificatePinningEnabled)
    }

    @Test
    fun `relaxing the policy also disables certificate pinning`() {
        TransportSecurityPolicy.update(httpsRequired = false)

        assertFalse(TransportSecurityPolicy.httpsRequired)
        assertFalse(TransportSecurityPolicy.certificatePinningEnabled)
    }

    @Test
    fun `deepseek client enforces the pins by default`() {
        val client = DeepSeekTlsPolicy.apply(OkHttpClient.Builder()).build()

        // 代理换上的陌生证书（模拟 VPN / 抓包）必须被拒绝。
        assertThrows(SSLPeerUnverifiedException::class.java) {
            client.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(proxyCertificate()))
        }
    }

    @Test
    fun `deepseek client stops enforcing the pins when the policy is relaxed`() {
        TransportSecurityPolicy.update(httpsRequired = false)

        val client = DeepSeekTlsPolicy.apply(OkHttpClient.Builder()).build()

        // 不抛异常即代表该宿主已不再做证书固定，VPN / 抓包代理可以正常替换证书链。
        client.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(proxyCertificate()))
    }

    @Test
    fun `cached client is rebuilt as soon as the policy flips`() {
        var builds = 0
        val cache = PinningAwareClientCache {
            builds += 1
            DeepSeekTlsPolicy.apply(OkHttpClient.Builder()).build()
        }

        val pinned = cache.current()
        assertThrows(SSLPeerUnverifiedException::class.java) {
            pinned.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(proxyCertificate()))
        }
        // 策略没变时复用同一实例，不能每次请求都重建。
        assertSame(pinned, cache.current())

        TransportSecurityPolicy.update(httpsRequired = false)

        val relaxed = cache.current()
        assertNotSame(pinned, relaxed)
        relaxed.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(proxyCertificate()))
        assertEquals(2, builds)
    }

    @Test
    fun `relaxed client accepts a certificate chain built on a user-installed CA`() {
        // 模拟用户的抓包代理：它用一张「用户证书」签发，Node/Android 默认都不信。
        val proxyCa = HeldCertificate.Builder()
            .certificateAuthority(1)
            .commonName("vpn-proxy-ca.example")
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("api.deepseek.com")
            .addSubjectAlternativeName("api.deepseek.com")
            .signedBy(proxyCa)
            .build()

        TransportSecurityPolicy.update(httpsRequired = false)
        val relaxed = TransportSecurityClient.apply(OkHttpClient.Builder()).build()
        val pinned = DeepSeekTlsPolicy.certificatePinner

        // 放宽态下 pinner 不再拦这张证书。
        relaxed.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(leaf.certificate))
        // 严格态仍然拦。
        assertThrows(SSLPeerUnverifiedException::class.java) {
            pinned.check(DeepSeekTlsPolicy.HOST, listOf(leaf.certificate))
        }
    }

    @Test
    fun `relaxed client installs a socket factory so user CAs are trusted at the TLS layer`() {
        // 只拆 pinner 不够：代理换上的证书还要过硬校验。放宽态必须换掉 sslSocketFactory。
        val strict = TransportSecurityClient.apply(OkHttpClient.Builder()).build() as OkHttpClient
        TransportSecurityPolicy.update(httpsRequired = false)
        val relaxed = TransportSecurityClient.apply(OkHttpClient.Builder()).build() as OkHttpClient

        // 严格态用平台默认的 socket factory（未显式覆盖时两者都等于系统默认实例）；
        // 放宽态则必须显式装一个基于 AndroidCAStore 的实例。
        assertNotSame(strict.sslSocketFactory, relaxed.sslSocketFactory)
    }

    private fun proxyCertificate() =
        HeldCertificate.Builder().commonName("vpn-proxy.example").build().certificate

    @Test
    fun `url fields reject http by default but accept it once relaxed`() {
        val baseUrl = ProviderConfigs.getConfigFields(ProviderType.CUSTOM).single { it.key == "baseUrl" }

        assertFalse(ProviderConfigs.validateFieldValue(baseUrl, "http://example.com/v1"))
        assertTrue(ProviderConfigs.validateFieldValue(baseUrl, "https://example.com/v1"))

        TransportSecurityPolicy.update(httpsRequired = false)

        assertTrue(ProviderConfigs.validateFieldValue(baseUrl, "http://example.com/v1"))
        assertTrue(ProviderConfigs.validateFieldValue(baseUrl, "https://example.com/v1"))
    }

    @Test
    fun `url schemes reflect the policy`() {
        assertEquals(setOf("https"), ProviderConfigs.urlSchemes())

        TransportSecurityPolicy.update(httpsRequired = false)

        assertEquals(setOf("http", "https"), ProviderConfigs.urlSchemes())
    }
}
