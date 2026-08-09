package com.balancesentinel.app.data.network

import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedResponseReaderTest {

    @Test
    fun `known content length over budget fails before opening stream`() {
        val opened = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val body = TrackingBody(
            bytes = "0123456789".toByteArray(),
            declaredLength = 10L,
            opened = opened,
            closed = closed
        )

        assertThrows(NetworkResponseException::class.java) {
            BoundedResponseReader.readBytes(body, maxBytes = 4L)
        }

        assertTrue("the body must be closed on rejection", closed.get())
        assertTrue("known-length rejection must happen before source()", !opened.get())
    }

    @Test
    fun `chunked body stops at max plus one and closes the body`() {
        val opened = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val body = TrackingBody(
            bytes = "0123456789".toByteArray(),
            declaredLength = -1L,
            opened = opened,
            closed = closed
        )

        assertThrows(NetworkResponseException::class.java) {
            BoundedResponseReader.readBytes(body, maxBytes = 4L)
        }

        assertTrue(opened.get())
        assertTrue("overflow must close the body", closed.get())
    }

    @Test
    fun `cancellation propagates and closes the response body`() {
        val closed = AtomicBoolean(false)
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long =
                    throw CancellationException("test cancellation")

                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }.buffer()
        }

        assertThrows(CancellationException::class.java) {
            BoundedResponseReader.readBytes(body, maxBytes = 32L)
        }
        assertTrue(closed.get())
    }

    @Test
    fun `reader retains bytes within the configured limit`() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            BoundedResponseReader.readBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3L)
        )
        assertEquals("ok", BoundedResponseReader.readText("ok".byteInputStream(), 8L))
    }

    @Test
    fun `decoded gzip output is bounded independently from encoded bytes`() {
        val compressed = java.io.ByteArrayOutputStream().use { output ->
            java.util.zip.GZIPOutputStream(output).use { it.write("x".repeat(512).toByteArray()) }
            output.toByteArray()
        }
        assertThrows(NetworkResponseException::class.java) {
            BoundedResponseReader.readBytes(
                GZIPInputStream(compressed.inputStream()),
                maxBytes = 32L
            )
        }
    }

    @Test
    fun `unexpected content type fails before text is accepted`() {
        val body = TrackingBody(
            bytes = "<html>not json</html>".toByteArray(),
            declaredLength = 21L,
            opened = AtomicBoolean(false),
            closed = AtomicBoolean(false),
            contentType = "text/html"
        )
        assertThrows(NetworkResponseException::class.java) {
            BoundedResponseReader.readText(body, 128L, "application/json")
        }
    }

    private class TrackingBody(
        private val bytes: ByteArray,
        private val declaredLength: Long,
        private val opened: AtomicBoolean,
        private val closed: AtomicBoolean,
        private val contentType: String = "application/json"
    ) : ResponseBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = declaredLength
        override fun source(): BufferedSource {
            opened.set(true)
            val source = bytes.inputStream().source()
            return object : ForwardingSource(source) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }.buffer()
        }

        override fun close() {
            closed.set(true)
        }
    }
}
