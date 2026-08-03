package com.balancesentinel.app.data.debug

data class DebugReportLabels(
    val status: String = "Status",
    val timestamp: String = "Timestamp",
    val duration: String = "Duration",
    val account: String = "Account",
    val provider: String = "Provider",
    val baseUrl: String = "Base URL",
    val endpoint: String = "Endpoint",
    val customScript: String = "Custom script",
    val yes: String = "yes",
    val requestHeaders: String = "Request headers",
    val requestBody: String = "Request body",
    val responseHeaders: String = "Response headers",
    val responseBody: String = "Response body",
    val error: String = "Error",
    val exceptionType: String = "Exception type",
    val stack: String = "Stack",
    val scriptCharacters: String = "Script characters",
    val scriptBytes: String = "Script bytes",
    val scriptSha256: String = "Script SHA-256"
)

object DebugReportFormatter {
    const val TRUNCATED_MARKER = "[TRUNCATED]"

    @Suppress("UNUSED_PARAMETER")
    fun formatEntry(
        entry: ApiDebugEntry,
        labels: DebugReportLabels = DebugReportLabels()
    ): String = SensitiveDataRedactor.redactText(
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

    @Suppress("UNUSED_PARAMETER")
    fun formatEntries(
        entries: Iterable<ApiDebugEntry>,
        labels: DebugReportLabels = DebugReportLabels()
    ): String =
        SensitiveDataRedactor.redactText(entries.joinToString("\n") { formatEntry(it) })

    fun formatText(text: String): String = SensitiveDataRedactor.redactText(text)
}
