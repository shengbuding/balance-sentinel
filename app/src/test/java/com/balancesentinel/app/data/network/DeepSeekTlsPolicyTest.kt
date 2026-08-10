package com.balancesentinel.app.data.network

import com.balancesentinel.app.data.api.ProviderError
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLPeerUnverifiedException
import java.io.IOException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekTlsPolicyTest {

    @Test
    fun `trusted current and backup fixtures are accepted and unrelated cert is rejected`() {
        val chain = fixtureChain()
        assertTrue(chain.size >= 3)

        DeepSeekTlsPolicy.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(chain[0]))
        DeepSeekTlsPolicy.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(chain[1]))

        val unrelated = HeldCertificate.Builder().commonName("unrelated.example").build().certificate
        assertThrows(SSLPeerUnverifiedException::class.java) {
            DeepSeekTlsPolicy.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(unrelated))
        }
    }

    @Test
    fun `pin failure does not fall back to an unpinned connection`() {
        val chain = fixtureChain()
        val currentOnly = CertificatePinner.Builder()
            .add(DeepSeekTlsPolicy.HOST, DeepSeekTlsPolicy.CURRENT_PIN)
            .build()

        assertThrows(SSLPeerUnverifiedException::class.java) {
            currentOnly.check(DeepSeekTlsPolicy.HOST, listOf(chain[1]))
        }
    }

    @Test
    fun `HeldCertificate handshake succeeds only with matching pinner`() {
        val serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val clientHandshake = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        val pin = "sha256/${Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(serverCertificate.keyPair.public.encoded)
        )}"
        val pinner = CertificatePinner.Builder().add("127.0.0.1", pin).build()
        val server = MockWebServer()
        server.useHttps(serverHandshake.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager)
                .certificatePinner(pinner)
                .build()
            client.newCall(Request.Builder().url(server.url("/" )).build()).execute().use { response ->
                assertEquals("ok", response.body?.string())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `HeldCertificate handshake rejects mismatched pin and maps to TLS network failure`() {
        val serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val clientHandshake = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        val wrongPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val server = MockWebServer()
        server.useHttps(serverHandshake.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("must-not-fallback"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager)
                .certificatePinner(CertificatePinner.Builder().add("127.0.0.1", wrongPin).build())
                .build()
            val failure = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body?.close() }
            }
            val mapped = ProviderError.NetworkError(com.balancesentinel.app.data.api.ProviderType.DEEPSEEK, failure)
            assertTrue(mapped.message.contains("TLS"))
            assertTrue(server.requestCount <= 1)
        } finally {
            server.shutdown()
        }
    }

    private fun fixtureChain(): List<X509Certificate> = (0..2).map { index ->
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("deepseek-chain/cert$index.der")) {
            "missing DeepSeek certificate fixture $index"
        }.use { it.readBytes() }
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }
}
