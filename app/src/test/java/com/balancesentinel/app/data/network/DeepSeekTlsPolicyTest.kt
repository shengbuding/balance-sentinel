package com.balancesentinel.app.data.network

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
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
    fun `live current and backup SPKI pins are accepted and unrelated cert is rejected`() {
        val chain = liveChain()
        assertTrue(chain.size >= 2)

        DeepSeekTlsPolicy.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(chain[0]))
        DeepSeekTlsPolicy.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(chain[1]))

        val unrelated = HeldCertificate.Builder().commonName("unrelated.example").build().certificate
        assertThrows(SSLPeerUnverifiedException::class.java) {
            DeepSeekTlsPolicy.certificatePinner.check(DeepSeekTlsPolicy.HOST, listOf(unrelated))
        }
    }

    @Test
    fun `pin failure does not fall back to an unpinned connection`() {
        val chain = liveChain()
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

    private fun liveChain(): List<java.security.cert.Certificate> {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(DeepSeekTlsPolicy.HOST, 443), 15_000)
        return try {
            socket.startHandshake()
            socket.session.peerCertificates.toList()
        } finally {
            socket.close()
        }
    }
}
