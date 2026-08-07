package com.balancesentinel.app.data.io

import java.io.InputStream

class BoundedInputLimitExceeded(message: String) : IllegalArgumentException(message)

object BoundedInput {
    fun readUtf8(input: InputStream, maxBytes: Int): String = error("Task 8 RED")
}
