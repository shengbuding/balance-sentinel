package com.balancesentinel.app.data.debug

import java.io.ByteArrayInputStream
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCaptureTest {
    // Mutation caught: truncating by characters or splitting a multibyte UTF-8 sequence.
    @Test
    fun `capture bounds multibyte text at a valid utf8 boundary`() {
        val raw = "a".repeat(MAX_CAPTURE_BYTES - 1) + "凭"

        val captured = DebugCapture.captureUtf8(ByteArrayInputStream(raw.toByteArray()), MAX_CAPTURE_BYTES)

        assertTrue(captured.truncated)
        assertEquals(MAX_CAPTURE_BYTES - 1L, captured.byteCount)
        assertEquals(MAX_CAPTURE_BYTES - 1, captured.text.toByteArray(Charsets.UTF_8).size)
        assertFalse(captured.text.contains('\uFFFD'))
    }

    // Mutation caught: treating an exact-limit body as truncated or retaining more than the limit.
    @Test
    fun `capture retains an exact byte limit without truncation`() {
        val captured = DebugCapture.captureUtf8(
            ByteArrayInputStream("x".repeat(MAX_CAPTURE_BYTES).toByteArray()),
            MAX_CAPTURE_BYTES
        )

        assertFalse(captured.truncated)
        assertEquals(MAX_CAPTURE_BYTES.toLong(), captured.byteCount)
        assertEquals(MAX_CAPTURE_BYTES, captured.text.length)
    }

    // Mutation caught: executing a one-shot body merely to create diagnostics.
    @Test
    fun `one shot request body is skipped without executing it`() {
        val body = CountingBody(oneShot = true)

        val captured = DebugCapture.captureRequest(body)

        assertEquals(DebugCapture.ONE_SHOT_SKIPPED, captured?.text)
        assertEquals(0, body.writeCount)
    }

    // Mutation caught: executing a duplex body merely to create diagnostics.
    @Test
    fun `duplex request body is skipped without executing it`() {
        val body = CountingBody(duplex = true)

        val captured = DebugCapture.captureRequest(body)

        assertEquals(DebugCapture.DUPLEX_SKIPPED, captured?.text)
        assertEquals(0, body.writeCount)
    }

    // Mutation caught: consuming the caller's response source instead of peeking it.
    @Test
    fun `response capture preserves the original response body`() {
        val originalBody = "streaming-payload".toResponseBody()
        val response = response(originalBody)

        val captured = DebugCapture.captureResponse(response)

        assertEquals("streaming-payload", captured.text)
        assertSame(originalBody, response.body)
        assertEquals("streaming-payload", response.body!!.string())
    }

    // Mutation caught: peeking without the extra byte needed to prove truncation.
    @Test
    fun `response capture detects and bounds an oversized body`() {
        val response = response("z".repeat(MAX_CAPTURE_BYTES + 1).toResponseBody())

        val captured = DebugCapture.captureResponse(response)

        assertTrue(captured.truncated)
        assertEquals(MAX_CAPTURE_BYTES.toLong(), captured.byteCount)
        assertEquals(MAX_CAPTURE_BYTES, captured.text.toByteArray().size)
    }

    private fun response(body: okhttp3.ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    private class CountingBody(
        private val oneShot: Boolean = false,
        private val duplex: Boolean = false
    ) : RequestBody() {
        var writeCount = 0

        override fun contentType(): MediaType? = null
        override fun isOneShot(): Boolean = oneShot
        override fun isDuplex(): Boolean = duplex

        override fun writeTo(sink: BufferedSink) {
            writeCount++
            sink.writeUtf8("body")
        }
    }
}
