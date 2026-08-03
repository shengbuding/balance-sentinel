package com.balancesentinel.app.data.debug

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ApiDebugEntry(
    val accountId: String,
    val url: String,
    val method: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val statusCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String,
    val timestamp: Long,
    val duration: Long,
    val error: String? = null,
    val accountLabel: String? = null,
    val providerType: String? = null,
    val baseUrl: String? = null,
    val endpoint: String? = null,
    val isCustomScript: Boolean = false,
    val scriptPreview: String? = null,
    val exceptionType: String? = null,
    val exceptionStack: String? = null,
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
    val errorTruncated: Boolean = false,
    val exceptionStackTruncated: Boolean = false,
    val scriptCharacterCount: Int? = null,
    val scriptByteCount: Int? = null,
    val scriptSha256: String? = null
)

object ApiDebugStore {
    private val entries = LinkedHashMap<Long, ApiDebugEntry>(16, 0.75f, true)
    private val accountEntries = mutableMapOf<String, MutableList<Long>>()
    private var nextId = 0L
    private var retainedBytes = 0L

    val currentBytes: Long
        @Synchronized get() = retainedBytes

    @Synchronized
    fun addEntry(entry: ApiDebugEntry) {
        val sanitized = sanitize(entry)
        val id = nextId++
        entries[id] = sanitized
        accountEntries.getOrPut(sanitized.accountId) { mutableListOf() }.add(id)
        retainedBytes += sizeOf(sanitized)

        while (retainedBytes > MAX_DEBUG_STORE_BYTES && entries.isNotEmpty()) {
            remove(entries.entries.first().key)
        }
        val ids = accountEntries[sanitized.accountId]
        while (ids != null && ids.size > MAX_ENTRIES_PER_ACCOUNT) {
            remove(ids.first())
        }
    }

    @Synchronized
    fun getEntries(accountId: String): List<ApiDebugEntry> {
        val ids = accountEntries[accountId]?.toList().orEmpty()
        return ids.mapNotNull { entries[it] }
    }

    @Synchronized
    fun clearEntries(accountId: String) {
        accountEntries[accountId]?.toList().orEmpty().forEach(::remove)
    }

    @Synchronized
    fun clearAll() {
        entries.clear()
        accountEntries.clear()
        retainedBytes = 0
        nextId = 0
    }

    @Synchronized
    fun getAccountIds(): Set<String> = accountEntries.keys.toSet()

    internal fun sanitizedEntry(entry: ApiDebugEntry): ApiDebugEntry = sanitize(entry)

    private fun remove(id: Long) {
        val removed = entries.remove(id) ?: return
        retainedBytes -= sizeOf(removed)
        accountEntries[removed.accountId]?.let { ids ->
            ids.remove(id)
            if (ids.isEmpty()) accountEntries.remove(removed.accountId)
        }
    }

    private fun sanitize(entry: ApiDebugEntry): ApiDebugEntry {
        val requestBody = entry.requestBody?.let(SensitiveDataRedactor::redactCaptured)
        val responseBody = SensitiveDataRedactor.redactCaptured(entry.responseBody)
        val error = entry.error?.let(SensitiveDataRedactor::redactCaptured)
        val stack = entry.exceptionStack?.let(SensitiveDataRedactor::redactCaptured)
        val script = entry.scriptPreview
        return entry.copy(
            url = redactUrlOrText(entry.url),
            method = SensitiveDataRedactor.redactText(entry.method),
            requestHeaders = SensitiveDataRedactor.redactHeaders(entry.requestHeaders),
            requestBody = requestBody?.text,
            responseHeaders = SensitiveDataRedactor.redactHeaders(entry.responseHeaders),
            responseBody = responseBody.text,
            error = error?.text,
            accountLabel = entry.accountLabel?.let(SensitiveDataRedactor::redactText),
            providerType = entry.providerType?.let(SensitiveDataRedactor::redactText),
            baseUrl = entry.baseUrl?.let(::redactUrlOrText),
            endpoint = entry.endpoint?.let(SensitiveDataRedactor::redactText),
            scriptPreview = null,
            exceptionType = entry.exceptionType?.let(SensitiveDataRedactor::redactText),
            exceptionStack = stack?.text,
            requestBodyTruncated = entry.requestBodyTruncated || requestBody?.truncated == true,
            responseBodyTruncated = entry.responseBodyTruncated || responseBody.truncated,
            errorTruncated = entry.errorTruncated || error?.truncated == true,
            exceptionStackTruncated = entry.exceptionStackTruncated || stack?.truncated == true,
            scriptCharacterCount = entry.scriptCharacterCount ?: script?.length,
            scriptByteCount = entry.scriptByteCount ?: script?.toByteArray(Charsets.UTF_8)?.size,
            scriptSha256 = script?.let(::sha256)
                ?: entry.scriptSha256?.let(SensitiveDataRedactor::redactText)
        )
    }

    private fun redactUrlOrText(value: String): String =
        value.toHttpUrlOrNull()?.let(SensitiveDataRedactor::redactUrl)
            ?: SensitiveDataRedactor.redactText(value)

    private fun sizeOf(entry: ApiDebugEntry): Long {
        var bytes = listOfNotNull(
            entry.accountId,
            entry.url,
            entry.method,
            entry.requestBody,
            entry.responseBody,
            entry.error,
            entry.accountLabel,
            entry.providerType,
            entry.baseUrl,
            entry.endpoint,
            entry.scriptPreview,
            entry.exceptionType,
            entry.exceptionStack,
            entry.scriptSha256
        ).sumOf(::utf8Size)
        entry.requestHeaders.forEach { (name, value) -> bytes += utf8Size(name) + utf8Size(value) }
        entry.responseHeaders.forEach { (name, value) -> bytes += utf8Size(name) + utf8Size(value) }
        return bytes
    }

    private fun utf8Size(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val MAX_ENTRIES_PER_ACCOUNT = 50
}
