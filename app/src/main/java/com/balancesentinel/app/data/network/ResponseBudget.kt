package com.balancesentinel.app.data.network

/**
 * Independent wire and decoded response budgets.
 *
 * The RED implementation intentionally keeps the compatibility surface small;
 * the GREEN implementation will enforce both values at their respective
 * transport boundaries.
 */
data class ResponseBudget(
    val maxEncodedBytes: Long,
    val maxDecodedBytes: Long,
    val endpoint: String = "response"
) {
    init {
        require(maxEncodedBytes > 0) { "maxEncodedBytes must be positive" }
        require(maxDecodedBytes > 0) { "maxDecodedBytes must be positive" }
    }

    val maxCompressedBytes: Long get() = maxEncodedBytes
    val maxDecompressedBytes: Long get() = maxDecodedBytes
    val encodedBytes: Long get() = maxEncodedBytes
    val decodedBytes: Long get() = maxDecodedBytes

    companion object {
        const val JSON_ENCODED_BYTES: Long = 256L * 1024L
        const val JSON_DECODED_BYTES: Long = 512L * 1024L
        const val SCRIPT_ENCODED_BYTES: Long = 512L * 1024L
        const val SCRIPT_DECODED_BYTES: Long = 1024L * 1024L
        const val UPDATE_ENCODED_BYTES: Long = 512L * 1024L
        const val UPDATE_DECODED_BYTES: Long = 1024L * 1024L
        const val CONSOLE_ENCODED_BYTES: Long = 1024L * 1024L
        const val CONSOLE_DECODED_BYTES: Long = 2L * 1024L * 1024L

        val DEEPSEEK = ResponseBudget(JSON_ENCODED_BYTES, JSON_DECODED_BYTES, "deepseek")
        val BALANCE = ResponseBudget(JSON_ENCODED_BYTES, JSON_DECODED_BYTES, "balance")
        val SCRIPT = ResponseBudget(SCRIPT_ENCODED_BYTES, SCRIPT_DECODED_BYTES, "script")
        val UPDATE = ResponseBudget(UPDATE_ENCODED_BYTES, UPDATE_DECODED_BYTES, "update")
        val CONSOLE = ResponseBudget(CONSOLE_ENCODED_BYTES, CONSOLE_DECODED_BYTES, "console")
    }
}
