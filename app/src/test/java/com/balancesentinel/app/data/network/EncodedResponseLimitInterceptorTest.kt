package com.balancesentinel.app.data.network

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class EncodedResponseLimitInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `encoded gzip bytes are rejected before decompression`() {
        val payload = gzip("x".repeat(512).toByteArray())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(okio.Buffer().write(payload))
        )
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(EncodedResponseLimitInterceptor(maxBytes = 8L))
            .build()

        client.newCall(Request.Builder().url(server.url("/bomb")).get().build()).execute().use {
            assertThrows(NetworkResponseException::class.java) { it.body!!.bytes() }
        }
    }

    @Test
    fun `encoded response within budget remains readable`() {
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(EncodedResponseLimitInterceptor(maxBytes = 64L))
            .build()

        client.newCall(Request.Builder().url(server.url("/ok")).get().build()).execute().use {
            assertEquals("ok", it.body!!.string())
        }
    }

    private fun gzip(value: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(value) }
        output.toByteArray()
    }
}
