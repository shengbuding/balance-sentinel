package com.balancesentinel.app.data.debug

import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiDebugStoreTest {
    @Before
    fun setUp() = ApiDebugStore.clearAll()

    @After
    fun tearDown() = ApiDebugStore.clearAll()

    // Mutation caught: omitting a retained string or header name from deterministic accounting.
    @Test
    fun `current bytes counts every retained string and header name`() {
        ApiDebugStore.addEntry(
            entry(
                accountId = "a", url = "u", method = "M",
                requestHeaders = mapOf("h" to "v"), requestBody = "q",
                responseHeaders = mapOf("r" to "s"), responseBody = "b",
                error = "e", accountLabel = "l", providerType = "p",
                baseUrl = "x", endpoint = "n", exceptionType = "t",
                exceptionStack = "k", scriptSha256 = "f"
            )
        )

        assertEquals(17L, ApiDebugStore.currentBytes)
    }

    // Mutation caught: enforcing only the per-account count ceiling.
    @Test
    fun `global budget evicts oldest entries across accounts`() {
        repeat(33) { index ->
            ApiDebugStore.addEntry(entry(accountId = "a$index", responseBody = "x".repeat(65_536)))
        }

        assertTrue(ApiDebugStore.currentBytes <= MAX_DEBUG_STORE_BYTES)
        assertFalse(ApiDebugStore.getAccountIds().contains("a0"))
        assertTrue(ApiDebugStore.getAccountIds().contains("a32"))
    }

    // Mutation caught: treating global retention as FIFO rather than access-order LRU.
    @Test
    fun `reading an account refreshes its entries in global access order`() {
        ApiDebugStore.addEntry(largeEntry("a", "a"))
        ApiDebugStore.addEntry(largeEntry("b", "b"))
        ApiDebugStore.getEntries("a")
        ApiDebugStore.addEntry(largeEntry("c", "c"))

        assertTrue(ApiDebugStore.getAccountIds().contains("a"))
        assertFalse(ApiDebugStore.getAccountIds().contains("b"))
        assertTrue(ApiDebugStore.getAccountIds().contains("c"))
    }

    // Mutation caught: failing to decrement bytes/order when the per-account limit evicts.
    @Test
    fun `per account ceiling remains a secondary bound with exact bytes`() {
        repeat(51) { index -> ApiDebugStore.addEntry(entry(accountId = "same", responseBody = index.toString())) }

        val retained = ApiDebugStore.getEntries("same")
        assertEquals(50, retained.size)
        assertEquals("1", retained.first().responseBody)
        assertEquals("50", retained.last().responseBody)
        assertEquals(1_441L, ApiDebugStore.currentBytes)
    }

    // Mutation caught: trusting direct callers to sanitize before insertion.
    @Test
    fun `direct callers cannot retain secrets or raw custom script source`() {
        val script = "return credentialValue_7f3a"
        ApiDebugStore.addEntry(
            entry(
                accountId = "acct",
                url = "https://example.com/?cursor=query-secret",
                requestHeaders = mapOf("Cookie" to "sid=cookie-secret"),
                requestBody = "apiKey=body-secret",
                responseBody = "{\"refresh_token\":\"response-secret\"}",
                isCustomScript = true,
                scriptPreview = script
            )
        )

        val stored = ApiDebugStore.getEntries("acct").single()
        val rendered = stored.toString()
        listOf("query-secret", "cookie-secret", "body-secret", "response-secret", script).forEach {
            assertFalse(rendered.contains(it))
        }
        assertNull(stored.scriptPreview)
        assertEquals(script.length, stored.scriptCharacterCount)
        assertEquals(script.toByteArray().size, stored.scriptByteCount)
        assertEquals(sha256(script), stored.scriptSha256)
    }

    // Mutation caught: sanitizing only the first whitespace-delimited credential token on insertion.
    @Test
    fun `direct callers cannot retain whitespace-bearing credential suffixes`() {
        ApiDebugStore.addEntry(
            entry(
                accountId = "acct",
                requestBody = "password=two word secret",
                responseBody = "password=\"quoted word secret\""
            )
        )

        val stored = ApiDebugStore.getEntries("acct").single()
        assertEquals("password=[REDACTED]", stored.requestBody)
        assertEquals("password=\"[REDACTED]\"", stored.responseBody)
    }

    // Mutation caught: sanitizing session material only at one debug boundary.
    @Test
    fun `store to formatter path removes nested session material and keeps safe siblings`() {
        val secrets = listOf("store-cookie-secret", "store-session-secret", "store-session-id-secret")
        ApiDebugStore.addEntry(
            entry(
                accountId = "acct",
                responseBody = """{"cookies":{"sid":"${secrets[0]}"},"session":"${secrets[1]}","sessionId":"${secrets[2]}","safe":"safe-sibling"}"""
            )
        )

        val formatted = DebugReportFormatter.formatEntry(ApiDebugStore.getEntries("acct").single())

        secrets.forEach { assertFalse("debug path leaked $it in: $formatted", formatted.contains(it)) }
        assertTrue(formatted.contains("safe-sibling"))
        assertTrue(formatted.contains("[REDACTED]"))
    }

    // Mutation caught: retaining a single sanitized entry that exceeds the global budget.
    @Test
    fun `oversized sanitized entry evicts itself`() {
        ApiDebugStore.addEntry(
            entry(
                accountId = "oversized",
                requestHeaders = (0 until 40).associate { "X-Debug-$it" to "x".repeat(65_536) }
            )
        )

        assertTrue(ApiDebugStore.getEntries("oversized").isEmpty())
        assertEquals(0L, ApiDebugStore.currentBytes)
    }

    private fun entry(
        accountId: String,
        url: String = "https://example.com",
        method: String = "GET",
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String = "",
        error: String? = null,
        accountLabel: String? = null,
        providerType: String? = null,
        baseUrl: String? = null,
        endpoint: String? = null,
        isCustomScript: Boolean = false,
        scriptPreview: String? = null,
        exceptionType: String? = null,
        exceptionStack: String? = null,
        scriptSha256: String? = null
    ) = ApiDebugEntry(
        accountId, url, method, requestHeaders, requestBody, 200,
        responseHeaders, responseBody, 1L, 2L, error, accountLabel,
        providerType, baseUrl, endpoint, isCustomScript, scriptPreview,
        exceptionType, exceptionStack, scriptSha256 = scriptSha256
    )

    private fun largeEntry(accountId: String, value: String): ApiDebugEntry = entry(
        accountId = accountId,
        requestBody = value.repeat(65_536),
        responseBody = value.repeat(65_536),
        error = value.repeat(65_536),
        exceptionStack = value.repeat(65_536),
        requestHeaders = (0 until 8).associate { "X-Debug-$it" to value.repeat(65_536) }
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
