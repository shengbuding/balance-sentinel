package com.balancesentinel.app.data.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportFormatterTest {
    // Mutation caught: trusting pre-sanitized entries and omitting the formatter's final redaction pass.
    @Test
    fun `formatter removes seeded secrets from every retained field`() {
        val secrets = listOf(
            "query-secret", "header-secret", "request-secret", "response-header-secret",
            "response-secret", "error-secret", "stack-secret"
        )
        val entry = ApiDebugEntry(
            accountId = "acct",
            url = "https://example.com/?cursor=${secrets[0]}",
            method = "POST",
            requestHeaders = mapOf("Authorization" to secrets[1]),
            requestBody = "apiKey=${secrets[2]}",
            statusCode = 500,
            responseHeaders = mapOf("Set-Cookie" to "sid=${secrets[3]}"),
            responseBody = "{\"access_token\":\"${secrets[4]}\"}",
            timestamp = 1,
            duration = 2,
            error = "token=${secrets[5]}",
            exceptionStack = "password=${secrets[6]}"
        )

        val formatted = DebugReportFormatter.formatEntry(entry)

        secrets.forEach { assertFalse("formatter leaked $it in: $formatted", formatted.contains(it)) }
        assertTrue(formatted.contains("[REDACTED]"))
    }

    // Mutation caught: counting truncation metadata inside body budgets or omitting it from reports.
    @Test
    fun `formatter appends truncation metadata outside retained bodies`() {
        val entry = ApiDebugEntry(
            "acct", "https://example.com", "POST", emptyMap(), "request", 500,
            emptyMap(), "response", 1, 2, error = "error", exceptionStack = "stack",
            requestBodyTruncated = true, responseBodyTruncated = true,
            errorTruncated = true, exceptionStackTruncated = true
        )

        val formatted = DebugReportFormatter.formatEntry(entry)

        assertTrue(formatted.contains("request ${DebugReportFormatter.TRUNCATED_MARKER}"))
        assertTrue(formatted.contains("response ${DebugReportFormatter.TRUNCATED_MARKER}"))
        assertTrue(formatted.contains("error ${DebugReportFormatter.TRUNCATED_MARKER}"))
        assertTrue(formatted.contains("stack ${DebugReportFormatter.TRUNCATED_MARKER}"))
    }

    // Mutation caught: bypassing shared redaction for Console session/log clipboard and file text.
    @Test
    fun `arbitrary console and logger text receives final redaction`() {
        val raw = "Cookie: sid=session-secret\nlocalStorage access_token=storage-secret\nBearer logger-secret"

        val formatted = DebugReportFormatter.formatText(raw)

        listOf("session-secret", "storage-secret", "logger-secret").forEach {
            assertFalse(formatted.contains(it))
        }
        assertTrue(formatted.contains("[REDACTED]"))
    }
}
