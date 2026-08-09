package com.balancesentinel.app.data.network

import java.io.InputStream
import java.nio.charset.Charset
import okhttp3.MediaType
import okhttp3.ResponseBody

/**
 * Compatibility surface for bounded response reads.
 *
 * This RED implementation deliberately delegates to OkHttp's unbounded body
 * read so the new boundary tests demonstrate the old behavior before GREEN.
 */
class BoundedResponseReader(private val maxBytes: Long) {
    init {
        require(maxBytes > 0)
    }

    fun readBytes(body: ResponseBody): ByteArray = body.bytes()

    fun readText(body: ResponseBody, charset: Charset = Charsets.UTF_8): String =
        readBytes(body).toString(charset)

    fun readText(
        body: ResponseBody,
        expectedContentType: String,
        charset: Charset = Charsets.UTF_8
    ): String = readText(body, charset)

    fun readBytes(input: InputStream): ByteArray = input.readBytes()

    fun readText(input: InputStream, charset: Charset = Charsets.UTF_8): String =
        readBytes(input).toString(charset)

    companion object {
        fun readBytes(body: ResponseBody, maxBytes: Long): ByteArray =
            BoundedResponseReader(maxBytes).readBytes(body)

        fun readText(
            body: ResponseBody,
            maxBytes: Long,
            charset: Charset = Charsets.UTF_8
        ): String = BoundedResponseReader(maxBytes).readText(body, charset)

        fun readText(
            body: ResponseBody,
            maxBytes: Long,
            expectedContentType: String,
            charset: Charset = Charsets.UTF_8
        ): String = BoundedResponseReader(maxBytes).readText(body, expectedContentType, charset)

        fun readBytes(input: InputStream, maxBytes: Long): ByteArray =
            BoundedResponseReader(maxBytes).readBytes(input)

        fun readText(
            input: InputStream,
            maxBytes: Long,
            charset: Charset = Charsets.UTF_8
        ): String = BoundedResponseReader(maxBytes).readText(input, charset)
    }
}
