package com.balancesentinel.app.data.debug

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SensitiveDataRedactor {
    const val REDACTED = "[REDACTED]"
    private const val LEGACY_SK_REDACTED = "sk-***"

    private val json = Json { ignoreUnknownKeys = true }
    private val sensitiveKey = Regex(
        """(?i)^(?:api[-_]?key|apikey|api[-_]?secret|secret(?:[-_]?key)?|client[-_]?secret|password|passwd|access[-_]?token|refresh[-_]?token|token|authorization|proxy[-_]?authorization|cookie|set[-_]?cookie)$"""
    )
    private val keyValue = Regex(
        """(?i)(\b(?:api[-_]?key|apikey|api[-_]?secret|secret(?:[-_]?key)?|client[-_]?secret|password|passwd|access[-_]?token|refresh[-_]?token|token|authorization|proxy[-_]?authorization)\b\s*[:=]\s*)([^\s&,;\r\n]+)"""
    )
    private val quotedKeyValue = Regex(
        """(?i)([\"'](?:api[-_]?key|apikey|api[-_]?secret|secret(?:[-_]?key)?|client[-_]?secret|password|passwd|access[-_]?token|refresh[-_]?token|token|authorization)[\"']\s*:\s*[\"'])([^\"']*)([\"'])"""
    )
    private val usageScript = Regex("""(?im)(\busageScript\s*=\s*)([^\r\n,]+)""")
    private val cookieLine = Regex("""(?im)(\b(?:cookie|set-cookie)\s*[:=]\s*)([^\r\n]+)""")
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val skKey = Regex("""\bsk-[A-Za-z0-9_-]{6,}\b""")
    private val url = Regex("""https?://[^\s<>'\"]+""")

    fun redactUrl(url: HttpUrl): String {
        if (url.querySize == 0) return bounded(url.toString())
        val builder = url.newBuilder().query(null)
        repeat(url.querySize) { index ->
            builder.addQueryParameter(url.queryParameterName(index), REDACTED)
        }
        return bounded(
            builder.build().toString()
                .replace("%5BREDACTED%5D", REDACTED, ignoreCase = true)
        )
    }

    fun redactHeaders(headers: Headers): Map<String, String> = headers.names().associateWith { name ->
        if (isSensitiveHeader(name)) REDACTED else headers.values(name).joinToString(", ")
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (isSensitiveHeader(name)) REDACTED else redactText(value)
    }

    fun redactText(text: String): String = redactCaptured(text).text

    fun redactForClipboard(text: String): String = redactText(text)

    internal fun redactCaptured(text: String): CapturedText {
        val structured = redactJson(text)
        val sanitized = redactUrls(structured)
            .let { usageScript.replace(it, "$1$REDACTED") }
            .let { cookieLine.replace(it, "$1$REDACTED") }
            .let { bearer.replace(it, "Bearer $REDACTED") }
            .let { quotedKeyValue.replace(it, "$1$REDACTED$3") }
            .let { keyValue.replace(it, "$1$REDACTED") }
            .let { skKey.replace(it, LEGACY_SK_REDACTED) }
        return DebugCapture.captureUtf8(
            ByteArrayInputStream(sanitized.toByteArray(Charsets.UTF_8)),
            MAX_CAPTURE_BYTES
        )
    }

    internal fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase().replace('_', '-').trim()
        return normalized in setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
            "secret-key"
        ) || normalized.contains("token") || normalized.contains("secret") ||
            normalized.replace("-", "").contains("apikey")
    }

    private fun redactJson(text: String): String {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return text
        return redactElement(element).toString()
    }

    private fun redactElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (sensitiveKey.matches(key)) JsonPrimitive(REDACTED) else redactElement(value)
            }
        )
        is JsonArray -> JsonArray(element.map(::redactElement))
        else -> element
    }

    private fun redactUrls(text: String): String = url.replace(text) { match ->
        val candidate = match.value
        val trailing = candidate.takeLastWhile { it in ".,;:)]]}" }
        val parsedText = candidate.dropLast(trailing.length)
        val parsed = parsedText.toHttpUrlOrNull()
        if (parsed == null) candidate else redactUrl(parsed) + trailing
    }

    private fun bounded(text: String): String = DebugCapture.captureUtf8(
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
        MAX_CAPTURE_BYTES
    ).text
}
