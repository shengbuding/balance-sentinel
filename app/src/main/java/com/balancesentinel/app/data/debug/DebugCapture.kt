package com.balancesentinel.app.data.debug

import com.balancesentinel.app.BuildConfig
import java.io.InputStream
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

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
        val bytes = input.readBytes()
        return CapturedText(
            text = bytes.toString(Charsets.UTF_8),
            truncated = false,
            byteCount = bytes.size.toLong()
        )
    }

    fun captureRequest(body: RequestBody?): CapturedText? = body?.let {
        val buffer = Buffer()
        it.writeTo(buffer)
        val bytes = buffer.readByteArray()
        CapturedText(bytes.toString(Charsets.UTF_8), truncated = false, byteCount = bytes.size.toLong())
    }

    fun captureResponse(response: Response): CapturedText {
        val text = response.body?.string().orEmpty()
        return CapturedText(
            text = text,
            truncated = false,
            byteCount = text.toByteArray(Charsets.UTF_8).size.toLong()
        )
    }
}

object DebugCapturePolicy {
    fun enabled(debuggable: Boolean = BuildConfig.DEBUG): Boolean = debuggable
}
