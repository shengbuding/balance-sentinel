package com.balancesentinel.app.data.debug

import com.balancesentinel.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Timeout
import okio.buffer

const val MAX_CAPTURE_BYTES = 64 * 1024
const val MAX_DEBUG_STORE_BYTES = 2 * 1024 * 1024L

data class CapturedText(
    val text: String,
    val truncated: Boolean,
    val byteCount: Long
)

object DebugCapture {
    const val ONE_SHOT_SKIPPED = "[ONE-SHOT BODY SKIPPED]"
    const val DUPLEX_SKIPPED = "[DUPLEX BODY SKIPPED]"

    internal fun captureUtf8(
        input: InputStream,
        maxBytes: Int = MAX_CAPTURE_BYTES
    ): CapturedText {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes + 1, 8 * 1024))
        val chunk = ByteArray(8 * 1024)
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            if (read == 0) continue
            output.write(chunk, 0, read)
            remaining -= read
        }
        return captureBytes(output.toByteArray(), maxBytes)
    }

    fun captureRequest(body: RequestBody?): CapturedText? {
        body ?: return null
        if (body.isOneShot()) return marker(ONE_SHOT_SKIPPED)
        if (body.isDuplex()) return marker(DUPLEX_SKIPPED)

        val sink = BoundedCaptureSink(MAX_CAPTURE_BYTES + 1)
        val buffered = sink.buffer()
        body.writeTo(buffered)
        buffered.flush()
        return captureBytes(sink.bytes(), MAX_CAPTURE_BYTES)
    }

    fun captureResponse(response: Response): CapturedText {
        val peeked = response.peekBody(MAX_CAPTURE_BYTES.toLong() + 1)
        return captureUtf8(peeked.byteStream(), MAX_CAPTURE_BYTES)
    }

    internal fun captureText(text: String): CapturedText = captureUtf8(
        text.byteInputStream(Charsets.UTF_8),
        MAX_CAPTURE_BYTES
    )

    private fun marker(text: String) = CapturedText(
        text = text,
        truncated = false,
        byteCount = text.toByteArray(Charsets.UTF_8).size.toLong()
    )

    private fun captureBytes(bytes: ByteArray, maxBytes: Int): CapturedText {
        val truncated = bytes.size > maxBytes
        var retainedSize = minOf(bytes.size, maxBytes)
        var text: String
        while (true) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = runCatching {
                decoder.decode(ByteBuffer.wrap(bytes, 0, retainedSize)).toString()
            }.getOrNull()
            if (decoded != null) {
                text = decoded
                break
            }
            retainedSize--
        }
        return CapturedText(text, truncated, retainedSize.toLong())
    }

    private class BoundedCaptureSink(private val capacity: Int) : Sink {
        private val retained = Buffer()

        override fun write(source: Buffer, byteCount: Long) {
            var remaining = byteCount
            val keep = minOf(remaining, (capacity - retained.size).coerceAtLeast(0).toLong())
            if (keep > 0) {
                retained.write(source, keep)
                remaining -= keep
            }
            if (remaining > 0) source.skip(remaining)
        }

        override fun flush() = Unit
        override fun close() = Unit
        override fun timeout(): Timeout = Timeout.NONE
        fun bytes(): ByteArray = retained.readByteArray()
    }
}

object DebugCapturePolicy {
    fun enabled(debuggable: Boolean = BuildConfig.DEBUG): Boolean = debuggable
}
