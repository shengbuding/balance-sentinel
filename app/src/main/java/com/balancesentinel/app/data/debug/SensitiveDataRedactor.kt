package com.balancesentinel.app.data.debug

import okhttp3.Headers
import okhttp3.HttpUrl

object SensitiveDataRedactor {
    fun redactUrl(url: HttpUrl): String = url.toString()

    fun redactHeaders(headers: Headers): Map<String, String> = buildMap {
        for (index in 0 until headers.size) {
            put(headers.name(index), headers.value(index))
        }
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> = headers.toMap()

    fun redactText(text: String): String = text

    fun redactForClipboard(text: String): String = redactText(text)
}
