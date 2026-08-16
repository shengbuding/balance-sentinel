package com.balancesentinel.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSyncPolicyTest {
    @Test
    fun `accepts a bounded current config transfer`() {
        val metadata = metadata()

        assertEquals(
            SyncMetadataValidation.Valid,
            DesktopSyncPolicy.validate(metadata, NOW)
        )
    }

    @Test
    fun `rejects expired and oversized transfers`() {
        val expired = DesktopSyncPolicy.validate(
            metadata(expiresAtMillis = NOW),
            NOW
        )
        val oversized = DesktopSyncPolicy.validate(
            metadata(contentLength = DesktopSyncPolicy.MAX_CONFIG_BYTES + 1),
            NOW
        )

        assertTrue(expired is SyncMetadataValidation.Invalid)
        assertTrue(oversized is SyncMetadataValidation.Invalid)
    }

    @Test
    fun `rejects a transfer whose expiry is not after issuance`() {
        val inverted = DesktopSyncPolicy.validate(
            metadata(expiresAtMillis = NOW - 1L),
            NOW - 60_000L
        )

        assertTrue(inverted is SyncMetadataValidation.Invalid)
        assertEquals("invalid_time_window", (inverted as SyncMetadataValidation.Invalid).reason)
    }

    private fun metadata(
        expiresAtMillis: Long = NOW + 60_000L,
        contentLength: Long = 1024L
    ) = SyncTransferMetadata(
        schemaVersion = DesktopSyncPolicy.SCHEMA_VERSION,
        transferId = "transfer_0123456789",
        nonce = "nonce_012345678901",
        issuedAtMillis = NOW,
        expiresAtMillis = expiresAtMillis,
        payloadSha256 = "a".repeat(64),
        contentLength = contentLength,
        payloadKind = SyncPayloadKind.CONFIG
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
