package com.balancesentinel.app.data.debug

object DebugReportFormatter {
    const val TRUNCATED_MARKER = "[TRUNCATED]"

    fun formatEntry(entry: ApiDebugEntry): String = buildString {
        appendLine("${entry.method} ${entry.url}")
        appendLine("Status: ${entry.statusCode}")
        appendLine("Request headers: ${entry.requestHeaders}")
        entry.requestBody?.let { appendLine("Request body: $it") }
        appendLine("Response headers: ${entry.responseHeaders}")
        appendLine("Response body: ${entry.responseBody}")
        entry.error?.let { appendLine("Error: $it") }
        entry.exceptionStack?.let { appendLine("Stack: $it") }
    }

    fun formatEntries(entries: Iterable<ApiDebugEntry>): String =
        entries.joinToString("\n") { formatEntry(it) }

    fun formatText(text: String): String = text
}
