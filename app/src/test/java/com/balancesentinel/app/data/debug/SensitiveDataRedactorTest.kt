package com.balancesentinel.app.data.debug

import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {
    // Mutation caught: preserving any sensitive header value, even when the name uses mixed case.
    @Test
    fun `sensitive header values are fully redacted`() {
        val secrets = listOf(
            "bearer-value", "proxy-value", "cookie-value", "set-cookie-value",
            "x-key-value", "api-key-value", "secret-value", "token-value"
        )
        val headers = headersOf(
            "Authorization", secrets[0],
            "Proxy-Authorization", secrets[1],
            "Cookie", secrets[2],
            "Set-Cookie", secrets[3],
            "X-Api-Key", secrets[4],
            "API-Key", secrets[5],
            "Secret-Key", secrets[6],
            "Refresh-Token", secrets[7],
            "X-Trace", "trace-safe"
        )

        val redacted = SensitiveDataRedactor.redactHeaders(headers)

        secrets.forEach { assertFalse(redacted.toString().contains(it)) }
        assertEquals("trace-safe", redacted["X-Trace"])
        assertTrue(redacted.values.count { it == "[REDACTED]" } >= secrets.size)
    }

    // Mutation caught: redacting only credential-named query parameters.
    @Test
    fun `every url query value is redacted`() {
        val raw = "https://example.com/path?access_token=query-token&cursor=opaque-cursor&name=alice".toHttpUrl()

        val redacted = SensitiveDataRedactor.redactUrl(raw)

        listOf("query-token", "opaque-cursor", "alice").forEach {
            assertFalse(redacted.contains(it))
        }
        assertEquals(3, "[REDACTED]".toRegex(RegexOption.LITERAL).findAll(redacted).count())
    }

    // Mutation caught: handling only top-level JSON keys or only sk-prefixed credentials.
    @Test
    fun `nested json and non sk credentials are redacted`() {
        val secrets = listOf("body-token", "body-key", "ordinary-api-key", "password-value")
        val raw = """{"refresh_token":"${secrets[0]}","nested":{"secretKey":"${secrets[1]}","api_key":"${secrets[2]}"},"password":"${secrets[3]}","note":"safe-note"}"""

        val redacted = SensitiveDataRedactor.redactText(raw)

        secrets.forEach { assertFalse(redacted.contains(it)) }
        assertTrue(redacted.contains("safe-note"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    // Mutation caught: skipping form data and free-text credential patterns.
    @Test
    fun `form and free text credentials are redacted conservatively`() {
        val secrets = listOf("form-secret", "free-bearer", "plain-password", "session-cookie")
        val raw = "apiKey=${secrets[0]}&note=safe-value\nAuthorization: Bearer ${secrets[1]}\npassword=${secrets[2]}\nCookie: sid=${secrets[3]}"

        val redacted = SensitiveDataRedactor.redactText(raw)

        secrets.forEach { assertFalse(redacted.contains(it)) }
        assertTrue(redacted.contains("safe-value"))
    }

    // Mutation caught: allowing an exception/log payload to grow beyond the shared byte ceiling.
    @Test
    fun `redacted text is bounded in utf8 bytes`() {
        val redacted = SensitiveDataRedactor.redactText("凭".repeat(30_000) + " token=tail-secret")

        assertTrue(redacted.toByteArray(Charsets.UTF_8).size <= MAX_CAPTURE_BYTES)
        assertFalse(redacted.contains("tail-secret"))
    }
}
