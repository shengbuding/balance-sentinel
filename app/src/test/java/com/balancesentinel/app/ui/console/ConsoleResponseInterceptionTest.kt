package com.balancesentinel.app.ui.console

import android.net.Uri
import android.webkit.WebResourceRequest
import com.balancesentinel.app.data.console.ConsoleCookieSink
import com.balancesentinel.app.data.console.ConsoleOriginPolicy
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.MAX_CAPTURE_BYTES
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleResponseInterceptionTest {
    // Mutation caught: returning decompressed/re-encoded bytes under the original gzip headers.
    @Test
    fun `gzip transport bytes and headers stay exact while diagnostics are decoded and bounded`() {
        withHttpsServer { server ->
            val plainBytes = ("token=gzip-secret\n" + "x".repeat(70_000)).toByteArray()
            val compressedBytes = gzip(plainBytes)
            server.enqueue(
                MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setHeader("Content-Type", "text/plain; charset=utf-8")
                    .setHeader("Content-Encoding", "gzip")
                    .setBody(Buffer().write(compressedBytes))
            )
            val requestUrl = server.url("/api/gzip").toString()
            val request = request(requestUrl)
            val platform = platform(server)
            val entries = mutableListOf<ApiDebugEntry>()

            val response = interceptApiRequest(
                request = request,
                tag = platform.id,
                policy = ConsoleOriginPolicy(platform),
                debuggable = true,
                entrySink = entries::add
            )

            assertNotNull(response)
            assertArrayEquals(compressedBytes, response!!.data.readBytes())
            assertEquals(
                "gzip",
                response.responseHeaders.entries.single {
                    it.key.equals("Content-Encoding", ignoreCase = true)
                }.value
            )
            val diagnostic = entries.single()
            assertFalse(diagnostic.responseBody.contains("gzip-secret"))
            assertTrue(diagnostic.responseBody.contains("[REDACTED]"))
            assertTrue(diagnostic.responseBody.toByteArray().size <= MAX_CAPTURE_BYTES)
            assertTrue(diagnostic.responseBodyTruncated)
        }
    }

    // Mutation caught: decoding arbitrary transport bytes as UTF-8 and encoding them again.
    @Test
    fun `binary response transport bytes stay exact`() {
        withHttpsServer { server ->
            val binaryBytes = byteArrayOf(
                0x00,
                0xff.toByte(),
                0xfe.toByte(),
                0x80.toByte(),
                0x41,
                0x42
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(binaryBytes))
            )
            val requestUrl = server.url("/api/binary").toString()
            val request = request(requestUrl)
            val platform = platform(server)

            val response = interceptApiRequest(
                request = request,
                tag = platform.id,
                policy = ConsoleOriginPolicy(platform),
                debuggable = true,
                entrySink = {}
            )

            assertNotNull(response)
            assertArrayEquals(binaryBytes, response!!.data.readBytes())
        }
    }

    @Test
    fun `oversized console response is rejected instead of truncated success`() {
        withHttpsServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setHeader("Content-Type", "application/json")
                    .setBody(Buffer().write(ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }))
            )
            val platform = platform(server)
            val entries = mutableListOf<ApiDebugEntry>()

            val response = interceptApiRequest(
                request = request(server.url("/api/oversized").toString()),
                tag = platform.id,
                policy = ConsoleOriginPolicy(platform),
                debuggable = true,
                entrySink = entries::add
            )

            assertEquals(null, response)
            assertTrue(entries.isNotEmpty())
            assertTrue(entries.single().errorTruncated || entries.single().error != null)
        }
    }

    @Test
    fun `interception forwards repeated Set-Cookie fields independently and redacts diagnostics`() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val server = MockWebServer()

        try {
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", COOKIE_WITH_EXPIRES)
                    .addHeader("Set-Cookie", SECOND_COOKIE)
                    .addHeader("X-Trace", "trace-value")
                    .setBody("{}")
            )
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

            val requestUrl = server.url("/api/usage").toString()
            val platform = ConsolePlatform(
                id = "local",
                name = "Local",
                loginUrl = server.url("/sign-in").toString(),
                dashboardUrl = server.url("/overview").toString(),
                successUrlPatterns = listOf("/overview")
            )
            val request = mockk<WebResourceRequest>()
            every { request.url } returns Uri.parse(requestUrl)
            every { request.method } returns "GET"
            every { request.requestHeaders } returns emptyMap()
            val entries = mutableListOf<ApiDebugEntry>()
            val responseCookies = RecordingCookieSink()

            val response = interceptApiRequest(
                request = request,
                tag = platform.id,
                policy = ConsoleOriginPolicy(platform),
                debuggable = true,
                entrySink = entries::add,
                responseCookieSink = responseCookies
            )

            assertNotNull(response)
            assertEquals(
                setOf(
                    requestUrl to COOKIE_WITH_EXPIRES,
                    requestUrl to SECOND_COOKIE
                ),
                responseCookies.writes.toSet()
            )
            assertEquals(2, responseCookies.writes.size)
            assertEquals(1, responseCookies.flushCalls)
            assertFalse(response!!.responseHeaders.keys.any { it.equals("Set-Cookie", ignoreCase = true) })
            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals("console:${platform.id}", entry.accountId)
            assertTrue(entry.responseHeaders["X-Trace"] == "trace-value")
            assertFalse(entry.responseHeaders.keys.any { it.equals("Set-Cookie", ignoreCase = true) })
            assertFalse(entry.toString().contains("alpha-secret"))
            assertFalse(entry.toString().contains("beta-secret"))
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier)
            server.shutdown()
        }
    }

    // Mutation caught: installing a Release callback that replaces WebView default networking.
    @Test
    fun `policy false leaves console request to default WebView networking`() {
        val request = mockk<WebResourceRequest>()
        every { request.url } returns Uri.parse("https://platform.deepseek.com/api/usage")
        every { request.method } returns "GET"
        every { request.requestHeaders } returns emptyMap()
        val platform = ConsolePlatform(
            id = "deepseek",
            name = "DeepSeek",
            loginUrl = "https://platform.deepseek.com/sign_in",
            dashboardUrl = "https://platform.deepseek.com/overview",
            successUrlPatterns = listOf("/overview")
        )
        val entries = mutableListOf<ApiDebugEntry>()

        val response = interceptApiRequest(
            request = request,
            tag = platform.id,
            policy = ConsoleOriginPolicy(platform),
            debuggable = false,
            entrySink = entries::add
        )

        assertEquals(null, response)
        assertTrue(entries.isEmpty())
    }

    // Mutation caught: admitting an API request by host while ignoring its alternate port.
    @Test
    fun `same host alternate port receives no intercepted credentials while configured port remains allowed`() {
        withHttpsServers { configured, alternate ->
            configured.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            alternate.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val platform = ConsolePlatform(
                id = "local",
                name = "Local",
                loginUrl = configured.url("/sign-in").toString(),
                dashboardUrl = configured.url("/overview").toString(),
                successUrlPatterns = listOf("/overview")
            )
            val policy = ConsoleOriginPolicy(platform)
            val entries = mutableListOf<ApiDebugEntry>()
            val credentials = mapOf(
                "Authorization" to "Bearer alternate-port-secret",
                "Cookie" to "session=alternate-port-cookie"
            )

            val denied = interceptApiRequest(
                request = request(alternate.url("/api/usage").toString(), credentials),
                tag = platform.id,
                policy = policy,
                debuggable = true,
                entrySink = entries::add
            )

            assertEquals(null, denied)
            assertEquals(0, alternate.requestCount)
            assertTrue(entries.isEmpty())

            val allowed = interceptApiRequest(
                request = request(configured.url("/api/usage").toString(), credentials),
                tag = platform.id,
                policy = policy,
                debuggable = true,
                entrySink = entries::add
            )

            assertNotNull(allowed)
            assertEquals(1, configured.requestCount)
            assertEquals("Bearer alternate-port-secret", configured.takeRequest().getHeader("Authorization"))
            assertEquals(1, entries.size)
        }
    }

    private fun withHttpsServer(block: (MockWebServer) -> Unit) {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val server = MockWebServer()

        try {
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            block(server)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier)
            server.shutdown()
        }
    }

    private fun withHttpsServers(block: (MockWebServer, MockWebServer) -> Unit) {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val configured = MockWebServer()
        val alternate = MockWebServer()

        try {
            configured.useHttps(serverCertificates.sslSocketFactory(), false)
            alternate.useHttps(serverCertificates.sslSocketFactory(), false)
            configured.start()
            alternate.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            block(configured, alternate)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier)
            configured.shutdown()
            alternate.shutdown()
        }
    }

    private fun request(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): WebResourceRequest = mockk<WebResourceRequest>().also {
        every { it.url } returns Uri.parse(url)
        every { it.method } returns "GET"
        every { it.requestHeaders } returns headers
    }

    private fun platform(server: MockWebServer) = ConsolePlatform(
        id = "local",
        name = "Local",
        loginUrl = server.url("/sign-in").toString(),
        dashboardUrl = server.url("/overview").toString(),
        successUrlPatterns = listOf("/overview")
    )

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

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

    private companion object {
        const val COOKIE_WITH_EXPIRES =
            "session=alpha-secret; Expires=Wed, 21 Oct 2037 07:28:00 GMT; Path=/; Secure; HttpOnly"
        const val SECOND_COOKIE = "csrf=beta-secret; Path=/; Secure; SameSite=Lax"
    }
}
