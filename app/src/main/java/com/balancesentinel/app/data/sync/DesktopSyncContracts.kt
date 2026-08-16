package com.balancesentinel.app.data.sync

import java.io.InputStream
import java.io.OutputStream

enum class SyncPayloadKind { CONFIG, HISTORY }

data class SyncTransferMetadata(
    val schemaVersion: Int,
    val transferId: String,
    val nonce: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val payloadSha256: String,
    val contentLength: Long,
    val payloadKind: SyncPayloadKind
)

data class StagedSyncPayload(
    val transferId: String,
    val payloadKind: SyncPayloadKind,
    val payloadSha256: String,
    val byteCount: Long,
    val opaqueLocalHandle: String
)

data class SyncPreview(
    val transferId: String,
    val payloadKind: SyncPayloadKind,
    val summary: Map<String, Long>,
    val warnings: List<String>,
    val requiresExplicitReplaceConfirmation: Boolean = false
)

data class SyncReceipt(
    val transferId: String,
    val payloadKind: SyncPayloadKind,
    val payloadSha256: String,
    val completedAtMillis: Long,
    val applied: Boolean
)

/**
 * Transport-neutral boundary for a future paired desktop client.
 * Implementations must reuse the existing bounded config/history import pipelines.
 */
interface DesktopSyncPort {
    suspend fun export(
        payloadKind: SyncPayloadKind,
        destination: OutputStream
    ): SyncReceipt

    suspend fun stage(
        metadata: SyncTransferMetadata,
        source: InputStream
    ): StagedSyncPayload

    suspend fun preview(staged: StagedSyncPayload): SyncPreview

    suspend fun apply(
        staged: StagedSyncPayload,
        expectedSha256: String,
        confirmReplace: Boolean = false
    ): SyncReceipt
}

sealed interface SyncMetadataValidation {
    data object Valid : SyncMetadataValidation
    data class Invalid(val reason: String) : SyncMetadataValidation
}

object DesktopSyncPolicy {
    const val SCHEMA_VERSION = 1
    const val MAX_CONFIG_BYTES = 4L * 1024L * 1024L
    const val MAX_HISTORY_BYTES = 256L * 1024L * 1024L
    const val MAX_CLOCK_SKEW_MILLIS = 60_000L
    const val MAX_TRANSFER_LIFETIME_MILLIS = 5L * 60L * 1000L

    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val opaqueTokenPattern = Regex("^[A-Za-z0-9_-]{16,128}$")

    fun validate(
        metadata: SyncTransferMetadata,
        nowMillis: Long
    ): SyncMetadataValidation {
        if (metadata.schemaVersion != SCHEMA_VERSION) return invalid("unsupported_schema")
        if (!opaqueTokenPattern.matches(metadata.transferId)) return invalid("invalid_transfer_id")
        if (!opaqueTokenPattern.matches(metadata.nonce)) return invalid("invalid_nonce")
        if (!sha256Pattern.matches(metadata.payloadSha256.lowercase())) return invalid("invalid_digest")
        if (metadata.issuedAtMillis > nowMillis + MAX_CLOCK_SKEW_MILLIS) return invalid("issued_in_future")
        if (metadata.expiresAtMillis <= metadata.issuedAtMillis) return invalid("invalid_time_window")
        if (metadata.expiresAtMillis <= nowMillis) return invalid("expired")
        if (metadata.expiresAtMillis - metadata.issuedAtMillis > MAX_TRANSFER_LIFETIME_MILLIS) {
            return invalid("lifetime_too_long")
        }
        val maxBytes = when (metadata.payloadKind) {
            SyncPayloadKind.CONFIG -> MAX_CONFIG_BYTES
            SyncPayloadKind.HISTORY -> MAX_HISTORY_BYTES
        }
        if (metadata.contentLength !in 1..maxBytes) return invalid("payload_too_large")
        return SyncMetadataValidation.Valid
    }

    private fun invalid(reason: String) = SyncMetadataValidation.Invalid(reason)
}
