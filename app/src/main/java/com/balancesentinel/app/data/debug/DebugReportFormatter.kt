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

    fun formatEntry(
        entry: ApiDebugEntry,
        labels: DebugReportLabels = DebugReportLabels()
    ): String = SensitiveDataRedactor.redactAggregate(
        buildString {
            appendLine("${entry.method} ${entry.url}")
            appendLine("${labels.status}: ${entry.statusCode}")
            appendLine("${labels.timestamp}: ${entry.timestamp}")
            appendLine("${labels.duration}: ${entry.duration} ms")
            entry.accountLabel?.let { appendLine("${labels.account}: $it") }
            entry.providerType?.let { appendLine("${labels.provider}: $it") }
            entry.baseUrl?.let { appendLine("${labels.baseUrl}: $it") }
            entry.endpoint?.let { appendLine("${labels.endpoint}: $it") }
            if (entry.isCustomScript) appendLine("${labels.customScript}: ${labels.yes}")
            appendLine("${labels.requestHeaders}: ${entry.requestHeaders}")
            entry.requestBody?.let {
                append("${labels.requestBody}: $it")
                if (entry.requestBodyTruncated) append(" $TRUNCATED_MARKER")
                appendLine()
            }
            appendLine("${labels.responseHeaders}: ${entry.responseHeaders}")
            append("${labels.responseBody}: ${entry.responseBody}")
            if (entry.responseBodyTruncated) append(" $TRUNCATED_MARKER")
            appendLine()
            entry.error?.let {
                append("${labels.error}: $it")
                if (entry.errorTruncated) append(" $TRUNCATED_MARKER")
                appendLine()
            }
            entry.exceptionType?.let { appendLine("${labels.exceptionType}: $it") }
            entry.exceptionStack?.let {
                append("${labels.stack}: $it")
                if (entry.exceptionStackTruncated) append(" $TRUNCATED_MARKER")
                appendLine()
            }
            entry.scriptCharacterCount?.let { appendLine("${labels.scriptCharacters}: $it") }
            entry.scriptByteCount?.let { appendLine("${labels.scriptBytes}: $it") }
            entry.scriptSha256?.let { appendLine("${labels.scriptSha256}: $it") }
        }
    )

    fun formatEntries(
        entries: Iterable<ApiDebugEntry>,
        labels: DebugReportLabels = DebugReportLabels()
    ): String =
        SensitiveDataRedactor.redactAggregate(
            entries.joinToString("\n") { formatEntry(it, labels) }
        )

    fun formatText(text: String): String = SensitiveDataRedactor.redactAggregate(text)
}
