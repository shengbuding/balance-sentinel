package com.balancesentinel.app.data.model

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val usageFieldMapSerializer = MapSerializer(String.serializer(), String.serializer())

fun encodeUsageDisplayFields(fields: Map<String, String>): String =
    Json.encodeToString(usageFieldMapSerializer, fields.filter { (path, label) ->
        path.isNotBlank() && label.isNotBlank()
    })

fun decodeUsageDisplayFields(value: String?): Map<String, String> =
    value.orEmpty().let { encoded ->
        runCatching {
            Json.decodeFromString(usageFieldMapSerializer, encoded)
        }.getOrDefault(emptyMap())
    }

fun parseUsageDisplayFieldLines(value: String): Map<String, String> = value.lineSequence()
    .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val path = line.substring(0, separator).trim()
        val label = line.substring(separator + 1).trim()
        path.takeIf(String::isNotEmpty)?.let { it to label.ifBlank { it } }
    }
    .toMap()

fun formatUsageDisplayFieldLines(fields: Map<String, String>): String =
    fields.entries.joinToString("\n") { (path, label) -> "$path=${label.ifBlank { path }}" }
