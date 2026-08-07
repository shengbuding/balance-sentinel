package com.balancesentinel.app.data.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

class BoundedInputLimitExceeded(message: String) : IllegalArgumentException(message)

object BoundedInput {
    fun readUtf8(input: InputStream, maxBytes: Int): String {
        require(maxBytes >= 0)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw BoundedInputLimitExceeded("input exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
    const val MAX_ACCOUNTS = 256
    const val MAX_STRING_BYTES = 16 * 1024
    const val MAX_SCRIPT_BYTES = 256 * 1024
    const val MAX_JSON_DEPTH = 32
}
