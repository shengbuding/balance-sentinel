package com.balancesentinel.app.data.network

import java.io.IOException

/** Stable, bounded-response failures safe to expose at provider boundaries. */
class NetworkResponseException(
    val reason: Reason,
    val endpoint: String = "response",
    val limitBytes: Long? = null,
    val observedBytes: Long? = null,
    val statusCode: Int? = null,
    val responseContentType: String? = null,
    val limitedBody: String? = null,
    cause: Throwable? = null
) : IOException(messageFor(reason, endpoint, limitBytes, statusCode), cause) {

    enum class Reason {
        ENCODED_LIMIT,
        DECODED_LIMIT,
        CONTENT_TYPE,
        HTTP_STATUS,
        EMPTY_BODY,
        CANCELLED
    }

    val kind: Reason get() = reason
    val category: Reason get() = reason
    val limitType: Reason? get() = reason.takeIf {
        it == Reason.ENCODED_LIMIT || it == Reason.DECODED_LIMIT
    }
    val isEncodedLimit: Boolean get() = reason == Reason.ENCODED_LIMIT
    val isDecodedLimit: Boolean get() = reason == Reason.DECODED_LIMIT
    val status: Int? get() = statusCode
    val maxBytes: Long? get() = limitBytes
    val body: String? get() = limitedBody

    companion object {
        private fun messageFor(
            reason: Reason,
            endpoint: String,
            limitBytes: Long?,
            statusCode: Int?
        ): String = when (reason) {
            Reason.ENCODED_LIMIT -> "Response exceeded encoded byte limit for $endpoint"
            Reason.DECODED_LIMIT -> "Response exceeded decoded byte limit for $endpoint"
            Reason.CONTENT_TYPE -> "Response content type rejected for $endpoint"
            Reason.HTTP_STATUS -> "HTTP response failed for $endpoint (${statusCode ?: 0})"
            Reason.EMPTY_BODY -> "Response body was empty for $endpoint"
            Reason.CANCELLED -> "Response read was cancelled for $endpoint"
        }.let { base ->
            if (reason == Reason.ENCODED_LIMIT || reason == Reason.DECODED_LIMIT) {
                "$base (limit=${limitBytes ?: 0})"
            } else {
                base
            }
        }

        fun encodedLimit(endpoint: String, maxBytes: Long, observedBytes: Long? = null) =
            NetworkResponseException(
                reason = Reason.ENCODED_LIMIT,
                endpoint = endpoint,
                limitBytes = maxBytes,
                observedBytes = observedBytes
            )

        fun decodedLimit(endpoint: String, maxBytes: Long, observedBytes: Long? = null) =
            NetworkResponseException(
                reason = Reason.DECODED_LIMIT,
                endpoint = endpoint,
                limitBytes = maxBytes,
                observedBytes = observedBytes
            )

        fun contentType(endpoint: String, actual: String?) = NetworkResponseException(
            reason = Reason.CONTENT_TYPE,
            endpoint = endpoint,
            responseContentType = actual
        )
    }
}
