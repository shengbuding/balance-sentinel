package com.balancesentinel.app.data.repository

import java.io.InputStream
import com.balancesentinel.app.data.io.BoundedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

typealias BoundedInputLimitExceeded = com.balancesentinel.app.data.io.BoundedInputLimitExceeded

class ConfigImportParser {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(input: InputStream): AppConfig {
        val content = BoundedInput.readUtf8(input, BoundedInput.MAX_CONFIG_BYTES)
        val root = json.parseToJsonElement(content)
        validate(root, 1)
        val accounts = (root as? JsonObject)?.get("accounts") as? JsonArray
            ?: throw IllegalArgumentException("accounts is required")
        if (accounts.size > BoundedInput.MAX_ACCOUNTS) {
            throw BoundedInputLimitExceeded("too many accounts")
        }
        return json.decodeFromString<AppConfig>(content).let {
            if ((root as JsonObject).containsKey("version")) it else it.copy(version = 1)
        }
    }

    private fun validate(element: JsonElement, depth: Int, key: String? = null) {
        if (depth > BoundedInput.MAX_JSON_DEPTH) {
            throw BoundedInputLimitExceeded("JSON nesting exceeds ${BoundedInput.MAX_JSON_DEPTH}")
        }
        when (element) {
            is JsonObject -> element.forEach { (name, value) -> validate(value, depth + 1, name) }
            is JsonArray -> element.forEach { validate(it, depth + 1, key) }
            is JsonPrimitive -> if (element.isString) {
                val bytes = element.content.toByteArray(Charsets.UTF_8).size
                val max = if (key?.contains("script", ignoreCase = true) == true) {
                    BoundedInput.MAX_SCRIPT_BYTES
                } else {
                    BoundedInput.MAX_STRING_BYTES
                }
                if (bytes > max) throw BoundedInputLimitExceeded("field $key exceeds $max bytes")
            }
        }
    }
}
