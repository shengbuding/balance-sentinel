package com.balancesentinel.app.service

/** A process-bound lease proving that the foreground service is alive. */
data class ServiceLease(
    val processSessionId: String,
    val expiresAt: Long
) {
    fun isFresh(now: Long, expectedProcessSessionId: String): Boolean =
        processSessionId == expectedProcessSessionId && expiresAt > now
}
