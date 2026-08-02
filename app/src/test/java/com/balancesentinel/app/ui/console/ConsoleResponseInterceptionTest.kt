package com.balancesentinel.app.ui.console

import android.net.Uri
import android.webkit.WebResourceRequest
import com.balancesentinel.app.data.console.ConsoleOriginPolicy
import io.mockk.every
import io.mockk.mockk
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleResponseInterceptionTest {
    @Test
    fun `interception preserves Set-Cookie for transport and redacts diagnostics`() {
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
                    .addHeader("Set-Cookie", "session=transport-secret; Path=/; HttpOnly")
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
            val logs = mutableListOf<ApiLogEntry>()

            val response = interceptApiRequest(
                request = request,
                apiLogs = logs,
                tag = platform.id,
                policy = ConsoleOriginPolicy(platform)
            )

            assertNotNull(response)
            assertTrue(
                response!!.responseHeaders.entries.any { (name, value) ->
                    name.equals("Set-Cookie", ignoreCase = true) &&
                        value.contains("session=transport-secret")
                }
            )
            assertTrue(logs.single().responseHeaders["X-Trace"] == "trace-value")
            assertFalse(logs.single().responseHeaders.keys.any { it.equals("Set-Cookie", ignoreCase = true) })
            assertFalse(logs.single().responseHeaders.values.any { it.contains("transport-secret") })
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier)
            server.shutdown()
        }
    }
}
