package com.balancesentinel.app.data.debug

object DebugReportFormatter {
    const val TRUNCATED_MARKER = "[TRUNCATED]"

    fun formatEntry(entry: ApiDebugEntry): String = buildString {
        appendLine("${entry.method} ${entry.url}")
        appendLine("Status: ${entry.statusCode}")
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
        entry.exceptionStack?.let {
            append("Stack: $it")
            if (entry.exceptionStackTruncated) append(" $TRUNCATED_MARKER")
            appendLine()
        }
    }

    fun formatEntries(entries: Iterable<ApiDebugEntry>): String =
        entries.joinToString("\n") { formatEntry(it) }

    fun formatText(text: String): String = text
}
