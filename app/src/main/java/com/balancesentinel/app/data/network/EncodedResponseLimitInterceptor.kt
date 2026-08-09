package com.balancesentinel.app.data.network

import java.util.concurrent.CancellationException
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.Response
import okio.ForwardingSource
import okio.Source
import okio.buffer

/** Enforces the encoded (pre-decompression) response budget at the network edge. */
class EncodedResponseLimitInterceptor(
    private val maxBytes: Long,
    private val endpoint: String = "response"
) : Interceptor {
    constructor(budget: ResponseBudget) : this(budget.maxEncodedBytes, budget.endpoint)

    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = try {
            chain.proceed(chain.request())
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        val body = response.body ?: return response
        val declaredLength = body.contentLength()
        if (declaredLength >= 0L && declaredLength > maxBytes) {
            response.close()
            throw NetworkResponseException(
                reason = NetworkResponseException.Reason.ENCODED_LIMIT,
                endpoint = endpoint,
                limitBytes = maxBytes,
                observedBytes = declaredLength
            )
        }
        return response.newBuilder()
            .body(LimitedResponseBody(body))
            .build()
    }

    private inner class LimitedResponseBody(
        private val delegate: ResponseBody
    ) : ResponseBody() {
        private var limitedSource: okio.BufferedSource? = null

        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun source(): okio.BufferedSource {
            limitedSource?.let { return it }
            val source = LimitedSource(delegate.source()).buffer()
            limitedSource = source
            return source
        }

        override fun close() {
            runCatching { limitedSource?.close() }
                .onFailure { runCatching { delegate.close() } }
            if (limitedSource == null) delegate.close()
        }

        private inner class LimitedSource(source: Source) : ForwardingSource(source) {
            private var totalRead = 0L

            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (Thread.currentThread().isInterrupted) {
                    close()
                    throw CancellationException("Response read cancelled")
                }
                if (byteCount == 0L) return 0L
                val remaining = maxBytes - totalRead
                if (remaining < 0L) throw encodedLimit(totalRead)
                val read = try {
                    super.read(sink, minOf(byteCount, remaining + 1L))
                } catch (cancelled: CancellationException) {
                    close()
                    throw cancelled
                }
                if (read > 0L) {
                    totalRead += read
                    if (totalRead > maxBytes) {
                        close()
                        throw encodedLimit(totalRead)
                    }
                }
                return read
            }

            private fun encodedLimit(observed: Long) = NetworkResponseException(
                reason = NetworkResponseException.Reason.ENCODED_LIMIT,
                endpoint = endpoint,
                limitBytes = maxBytes,
                observedBytes = observed
            )
        }
    }
}
