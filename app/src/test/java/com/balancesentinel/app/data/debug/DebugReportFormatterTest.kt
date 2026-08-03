package com.balancesentinel.app.data.debug

import org.junit.Assert.assertEquals
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

    // Mutation caught: final formatting uses a whitespace-stopping credential matcher.
    @Test
    fun `formatter removes complete whitespace-bearing credential values`() {
        val entry = ApiDebugEntry(
            "acct", "https://example.com", "POST", emptyMap(),
            "password=two word request secret", 200, emptyMap(),
            "password=\"quoted response secret\"", 1, 2
        )

        val formatted = DebugReportFormatter.formatEntry(entry)

        listOf("word request secret", "response secret").forEach {
            assertFalse("formatter leaked credential suffix $it in: $formatted", formatted.contains(it))
        }
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

    // Mutation caught: applying the 65,536-byte field bound to the assembled report.
    @Test
    fun `maximum retained field keeps its truncation marker in the report`() {
        val entry = ApiDebugEntry(
            "acct", "https://example.com", "GET", emptyMap(), null, 200,
            emptyMap(), "x".repeat(65_536), 1, 2,
            responseBodyTruncated = true
        )

        val formatted = DebugReportFormatter.formatEntry(entry)

        assertTrue(
            "65,536 retained bytes must be followed by formatter metadata",
            formatted.contains("x ${DebugReportFormatter.TRUNCATED_MARKER}\n")
        )
    }

    // Mutation caught: bounding the assembled multi-entry report and silently dropping its tail.
    @Test
    fun `aggregate formatting retains later entries after a maximum field`() {
        val first = ApiDebugEntry(
            "first", "https://first.example", "FIRST", emptyMap(), null, 200,
            emptyMap(), "x".repeat(65_536), 1, 2,
            responseBodyTruncated = true
        )
        val second = ApiDebugEntry(
            "second", "https://later.example", "SECOND", emptyMap(), null, 204,
            emptyMap(), "tail", 3, 4
        )

        val formatted = DebugReportFormatter.formatEntries(listOf(first, second))

        assertTrue(
            "the second entry must survive aggregate final redaction",
            formatted.contains("SECOND https://later.example")
        )
    }

    // Mutation caught: ignoring supplied labels and rendering hard-coded English labels.
    @Test
    fun `formatter renders every supplied localized label`() {
        val labels = DebugReportLabels(
            status = "Estado",
            timestamp = "Marca temporal",
            duration = "Duracion",
            account = "Cuenta",
            provider = "Proveedor",
            baseUrl = "URL base",
            endpoint = "Ruta",
            customScript = "Script personalizado",
            yes = "si",
            requestHeaders = "Cabeceras solicitud",
            requestBody = "Cuerpo solicitud",
            responseHeaders = "Cabeceras respuesta",
            responseBody = "Cuerpo respuesta",
            error = "Fallo",
            exceptionType = "Tipo excepcion",
            stack = "Pila",
            scriptCharacters = "Caracteres script",
            scriptBytes = "Bytes script",
            scriptSha256 = "SHA-256 script"
        )
        val entry = ApiDebugEntry(
            accountId = "acct",
            url = "https://example.com",
            method = "GET",
            requestHeaders = emptyMap(),
            requestBody = "request",
            statusCode = 201,
            responseHeaders = emptyMap(),
            responseBody = "response",
            timestamp = 11,
            duration = 12,
            error = "failure",
            accountLabel = "account",
            providerType = "provider",
            baseUrl = "base",
            endpoint = "endpoint",
            isCustomScript = true,
            exceptionType = "type",
            exceptionStack = "trace",
            scriptCharacterCount = 13,
            scriptByteCount = 14,
            scriptSha256 = "digest"
        )

        val formatted = DebugReportFormatter.formatEntry(entry, labels)

        val expected = """
            GET https://example.com
            Estado: 201
            Marca temporal: 11
            Duracion: 12 ms
            Cuenta: account
            Proveedor: provider
            URL base: base
            Ruta: endpoint
            Script personalizado: si
            Cabeceras solicitud: {}
            Cuerpo solicitud: request
            Cabeceras respuesta: {}
            Cuerpo respuesta: response
            Fallo: failure
            Tipo excepcion: type
            Pila: trace
            Caracteres script: 13
            Bytes script: 14
            SHA-256 script: digest
        """.trimIndent() + "\n"
        assertEquals(expected, formatted)
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
