package com.balancesentinel.app.ui.console

import android.net.Uri
import android.webkit.WebResourceRequest
import com.balancesentinel.app.data.console.ConsoleCookieSink
import com.balancesentinel.app.data.console.ConsoleOriginPolicy
import com.balancesentinel.app.data.debug.ApiDebugEntry
import io.mockk.every
import io.mockk.mockk
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleResponseInterceptionTest {
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
