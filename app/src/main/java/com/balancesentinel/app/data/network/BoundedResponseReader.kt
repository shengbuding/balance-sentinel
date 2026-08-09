package com.balancesentinel.app.data.network

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import okhttp3.ResponseBody

/**
 * Shared bounded response reader for OkHttp and URLConnection response bodies.
 */
class BoundedResponseReader(
    private val maxBytes: Long,
    private val endpoint: String = "response"
) {
    init {
        require(maxBytes > 0)
    }

    fun readBytes(body: ResponseBody): ByteArray = readBytes(body, expectedContentType = null)

    fun read(body: ResponseBody): ByteArray = readBytes(body)

    fun readUtf8(body: ResponseBody): String = readText(body)

    fun readBytes(body: ResponseBody, expectedContentType: String?): ByteArray {
        val declaredLength = body.contentLength()
        try {
            if (declaredLength >= 0L && declaredLength > maxBytes) {
                throw NetworkResponseException(
                    reason = NetworkResponseException.Reason.DECODED_LIMIT,
                    endpoint = endpoint,
                    limitBytes = maxBytes,
                    observedBytes = declaredLength
                )
            }
            validateContentType(body.contentType()?.toString(), expectedContentType)
            return readBytes(
                input = body.byteStream(),
                declaredLength = declaredLength,
                closeInput = true
            )
        } finally {
            runCatching { body.close() }
        }
    }

    fun readText(body: ResponseBody, charset: Charset = Charsets.UTF_8): String =
        readText(body, expectedContentType = null, charset = charset)

    fun readText(
        body: ResponseBody,
        expectedContentType: String?,
        charset: Charset = Charsets.UTF_8
    ): String {
        val contentType = body.contentType()
        val bytes = readBytes(body, expectedContentType)
        val bodyCharset = runCatching { contentType?.charset(charset) }
            .getOrNull()
            ?: charset
        return bytes.toString(bodyCharset)
    }

    fun readBytes(input: InputStream): ByteArray =
        readBytes(input, declaredLength = -1L, closeInput = true)

    fun read(input: InputStream): ByteArray = readBytes(input)

    fun readUtf8(input: InputStream): String = readText(input)

    fun readBytes(
        input: InputStream,
        declaredLength: Long = -1L,
        closeInput: Boolean = true
    ): ByteArray {
        if (declaredLength >= 0L && declaredLength > maxBytes) {
            if (closeInput) runCatching { input.close() }
            throw NetworkResponseException(
                reason = NetworkResponseException.Reason.DECODED_LIMIT,
                endpoint = endpoint,
                limitBytes = maxBytes,
                observedBytes = declaredLength
            )
        }
        val output = ByteArrayOutputStream(minOf(maxBytes + 1L, 8L * 1024L).toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        try {
            while (true) {
                checkCancelled()
                val remaining = maxBytes - total
                if (remaining < 0L) {
                    throw decodedLimit(total)
                }
                val requested = minOf(buffer.size.toLong(), remaining + 1L).toInt()
                if (requested <= 0) throw decodedLimit(total + 1L)
                val read = input.read(buffer, 0, requested)
                if (read < 0) break
                if (read == 0) continue
                total += read.toLong()
                output.write(buffer, 0, read)
                if (total > maxBytes) throw decodedLimit(total)
            }
            return output.toByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (closeInput) runCatching { input.close() }
        }
    }

    fun readText(input: InputStream, charset: Charset = Charsets.UTF_8): String =
        readBytes(input).toString(charset)

    private fun decodedLimit(observed: Long) = NetworkResponseException(
        reason = NetworkResponseException.Reason.DECODED_LIMIT,
        endpoint = endpoint,
        limitBytes = maxBytes,
        observedBytes = observed
    )

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Response read cancelled")
        }
    }

    private fun validateContentType(actual: String?, expected: String?) {
        if (expected == null || actual == null) return
        val expectedType = expected.substringBefore(';').trim().lowercase()
        val actualType = actual.substringBefore(';').trim().lowercase()
        if (expectedType == actualType) return
        val expectedJson = expectedType == "application/json" || expectedType.endsWith("+json")
        val actualJson = actualType == "application/json" || actualType.endsWith("+json")
        val acceptedLegacyText = actualType == "text/plain" || actualType == "text/*"
        if (!(expectedJson && (actualJson || acceptedLegacyText))) {
            throw NetworkResponseException(
                reason = NetworkResponseException.Reason.CONTENT_TYPE,
                endpoint = endpoint,
                responseContentType = actual
            )
        }
    }

    companion object {
        fun readBytes(body: ResponseBody, maxBytes: Long): ByteArray =
            BoundedResponseReader(maxBytes).readBytes(body)

        fun readBytes(body: ResponseBody, budget: ResponseBudget): ByteArray =
            BoundedResponseReader(budget.maxDecodedBytes, budget.endpoint).readBytes(body)

        fun read(body: ResponseBody, maxBytes: Long): ByteArray =
            BoundedResponseReader(maxBytes).readBytes(body)

        fun read(body: ResponseBody, budget: ResponseBudget): ByteArray =
            BoundedResponseReader(budget.maxDecodedBytes, budget.endpoint).readBytes(body)

        fun readBytes(
            body: ResponseBody,
            maxBytes: Long,
            expectedContentType: String?
        ): ByteArray = BoundedResponseReader(maxBytes).readBytes(body, expectedContentType)

        fun readText(
            body: ResponseBody,
            maxBytes: Long,
            charset: Charset = Charsets.UTF_8
        ): String = BoundedResponseReader(maxBytes).readText(body, charset)

        fun readText(body: ResponseBody, budget: ResponseBudget): String =
            BoundedResponseReader(budget.maxDecodedBytes, budget.endpoint).readText(body)

        fun readText(
            body: ResponseBody,
            maxBytes: Long,
            expectedContentType: String,
            charset: Charset = Charsets.UTF_8
        ): String = BoundedResponseReader(maxBytes).readText(body, expectedContentType, charset)

        fun readBytes(input: InputStream, maxBytes: Long): ByteArray =
            BoundedResponseReader(maxBytes).readBytes(input)

        fun readBytes(
            input: InputStream,
            maxBytes: Long,
            contentLength: Long,
            closeInput: Boolean = true
        ): ByteArray = BoundedResponseReader(maxBytes).readBytes(input, contentLength, closeInput)

        fun readBytes(input: InputStream, budget: ResponseBudget): ByteArray =
            BoundedResponseReader(budget.maxDecodedBytes, budget.endpoint).readBytes(input)

        fun readText(
            input: InputStream,
            maxBytes: Long,
            charset: Charset = Charsets.UTF_8
        ): String = BoundedResponseReader(maxBytes).readText(input, charset)

    }
}
