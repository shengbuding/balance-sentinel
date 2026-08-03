package com.balancesentinel.app.data.debug

object DebugReportFormatter {
    const val TRUNCATED_MARKER = "[TRUNCATED]"

    fun formatEntry(entry: ApiDebugEntry): String = SensitiveDataRedactor.redactText(
        buildString {
            appendLine("${entry.method} ${entry.url}")
            appendLine("Status: ${entry.statusCode}")
            appendLine("Timestamp: ${entry.timestamp}")
            appendLine("Duration: ${entry.duration} ms")
            entry.accountLabel?.let { appendLine("Account: $it") }
            entry.providerType?.let { appendLine("Provider: $it") }
            entry.baseUrl?.let { appendLine("Base URL: $it") }
            entry.endpoint?.let { appendLine("Endpoint: $it") }
            if (entry.isCustomScript) appendLine("Custom script: yes")
            appendLine("Request headers: ${entry.requestHeaders}")
            entry.requestBody?.let {
                append("Request body: $it")
                if (entry.requestBodyTruncated) append(" $TRUNCATED_MARKER")
                appendLine()
            }
            appendLine("Response headers: ${entry.responseHeaders}")
            append("Response body: ${entry.responseBody}")
            if (entry.responseBodyTruncated) append(" $TRUNCATED_MARKER")
            appendLine()
            entry.error?.let {
                append("Error: $it")
                if (entry.errorTruncated) append(" $TRUNCATED_MARKER")
                appendLine()
            }
            entry.exceptionType?.let { appendLine("Exception type: $it") }
            entry.exceptionStack?.let {
                append("Stack: $it")
                if (entry.exceptionStackTruncated) append(" $TRUNCATED_MARKER")
                appendLine()
            }
            entry.scriptCharacterCount?.let { appendLine("Script characters: $it") }
            entry.scriptByteCount?.let { appendLine("Script bytes: $it") }
            entry.scriptSha256?.let { appendLine("Script SHA-256: $it") }
        }
    )

    fun formatEntries(entries: Iterable<ApiDebugEntry>): String =
        SensitiveDataRedactor.redactText(entries.joinToString("\n") { formatEntry(it) })

    fun formatText(text: String): String = SensitiveDataRedactor.redactText(text)
}
