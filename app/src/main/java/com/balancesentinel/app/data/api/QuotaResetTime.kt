package com.balancesentinel.app.data.api

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/** Parses provider reset timestamps without exposing raw machine timestamps to the UI. */
fun quotaResetEpochMillis(value: String?): Long? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    raw.toLongOrNull()?.let { numeric ->
        val millis = if (abs(numeric) < EPOCH_MILLIS_THRESHOLD) numeric * 1_000L else numeric
        return millis.takeIf { it > 0L }
    }
    return sequenceOf(
        { Instant.parse(raw).toEpochMilli() },
        { OffsetDateTime.parse(raw).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(raw).toInstant().toEpochMilli() },
        { LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    ).firstNotNullOfOrNull { parser -> runCatching(parser).getOrNull() }
}

private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
