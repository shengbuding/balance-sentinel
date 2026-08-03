package com.balancesentinel.app.data.debug

import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugInterceptorTest {
    // Mutation caught: preserving query, header, body, response, or cookie credentials in an entry.
    @Test
    fun `successful exchange is sanitized at every retained boundary`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "sid=response-cookie-secret")
                    .setBody("{\"access_token\":\"response-body-secret\"}")
            )
            val captured = mutableListOf<ApiDebugEntry>()
            val client = client(captured)
            val request = Request.Builder()
                .url(server.url("/balance?cursor=query-secret"))
                .header("Authorization", "Bearer authorization-secret")
                .header("Cookie", "sid=request-cookie-secret")
                .post("apiKey=request-body-secret".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            client.newCall(request).execute().use { it.body!!.string() }

            val entry = captured.single()
            val retained = entry.toString()
            listOf(
                "query-secret", "authorization-secret", "request-cookie-secret",
                "request-body-secret", "response-cookie-secret", "response-body-secret"
            ).forEach { assertFalse(retained.contains(it)) }
            assertTrue(retained.contains("[REDACTED]"))
            assertEquals(200, entry.statusCode)
        } finally {
            server.shutdown()
        }
    }

    // Mutation caught: allocating or retaining a complete request/response body.
    @Test
    fun `request and response bodies are independently bounded while caller receives full response`() {
        val server = MockWebServer().also { it.start() }
        val requestBody = "q".repeat(MAX_CAPTURE_BYTES + 10)
        val responseBody = "凭".repeat(30_000)
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
            val captured = mutableListOf<ApiDebugEntry>()
            val request = Request.Builder()
                .url(server.url("/large"))
                .post(requestBody.toRequestBody())
                .build()

            val returned = client(captured).newCall(request).execute().use { it.body!!.string() }

            val entry = captured.single()
            assertEquals(responseBody, returned)
            assertTrue(entry.requestBody!!.toByteArray().size <= MAX_CAPTURE_BYTES)
            assertTrue(entry.responseBody.toByteArray().size <= MAX_CAPTURE_BYTES)
            assertTrue(entry.requestBodyTruncated)
            assertTrue(entry.responseBodyTruncated)
        } finally {
            server.shutdown()
        }
    }

    // Mutation caught: consuming and rebuilding the response body returned by the chain.
    @Test
    fun `interceptor returns the chain response with its original body instance`() {
        val request = Request.Builder().url("https://example.com/data").build()
        val body = "stream-content".toResponseBody()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response

        val returned = DebugInterceptor("acct", entrySink = {}).intercept(chain)

        assertSame(response, returned)
        assertSame(body, returned.body)
        assertEquals("stream-content", returned.body!!.string())
    }

    // Mutation caught: retaining an unbounded/raw non-2xx response twice as body and error.
    @Test
    fun `non successful response captures bounded redacted body and error`() {
        val server = MockWebServer().also { it.start() }
        val raw = "refresh_token=error-secret&padding=" + "x".repeat(MAX_CAPTURE_BYTES + 100)
        try {
            server.enqueue(MockResponse().setResponseCode(422).setBody(raw))
            val captured = mutableListOf<ApiDebugEntry>()

            client(captured).newCall(Request.Builder().url(server.url("/error")).build())
                .execute().close()

            val entry = captured.single()
            assertEquals(422, entry.statusCode)
            assertFalse(entry.responseBody.contains("error-secret"))
            assertFalse(entry.error.orEmpty().contains("error-secret"))
            assertTrue(entry.responseBody.toByteArray().size <= MAX_CAPTURE_BYTES)
            assertTrue(entry.error.orEmpty().toByteArray().size <= MAX_CAPTURE_BYTES)
            assertTrue(entry.responseBodyTruncated)
            assertTrue(entry.errorTruncated)
        } finally {
            server.shutdown()
        }
    }

    // Mutation caught: retaining an unbounded exception message or stack with embedded credentials.
    @Test
    fun `network exception entry is bounded and redacted before rethrow`() {
        val captured = mutableListOf<ApiDebugEntry>()
        val throwing = Interceptor {
            throw IOException("token=exception-secret " + "凭".repeat(30_000))
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugInterceptor("acct", entrySink = captured::add))
            .addInterceptor(throwing)
            .build()
        val request = Request.Builder()
            .url("https://example.com/fail?cursor=query-secret")
            .header("Authorization", "Bearer header-secret")
            .build()

        runCatching { client.newCall(request).execute() }

        val entry = captured.single()
        val retained = entry.toString()
        listOf("exception-secret", "query-secret", "header-secret").forEach {
            assertFalse(retained.contains(it))
        }
        assertTrue(entry.error.orEmpty().toByteArray().size <= MAX_CAPTURE_BYTES)
        assertTrue(entry.exceptionStack.orEmpty().toByteArray().size <= MAX_CAPTURE_BYTES)
        assertTrue(entry.errorTruncated)
        assertTrue(entry.exceptionStackTruncated)
    }

    private fun client(captured: MutableList<ApiDebugEntry>) = OkHttpClient.Builder()
        .addInterceptor(DebugInterceptor("acct", entrySink = captured::add))
        .build()
}
