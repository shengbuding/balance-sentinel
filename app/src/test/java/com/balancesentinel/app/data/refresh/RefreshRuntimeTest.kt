package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.widget.AccountBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRuntimeTest {
    @Test
    fun `first failure persists marker without claiming stale cached data`() {
        var markerCalls = 0

        val result = RefreshRuntime.projectStaleFailure(
            accountId = "acct",
            failure = RefreshFailure.NetworkFailure("offline"),
            cached = emptyList(),
            markStale = { markerCalls++ }
        )

        assertEquals(1, markerCalls)
        assertFalse(result.stale)
        assertNull(result.dataTimestamp)
        assertEquals("offline", result.lastError)
    }

    @Test
    fun `cached failure preserves stale timestamp when marker succeeds`() {
        val result = RefreshRuntime.projectStaleFailure(
            accountId = "acct",
            failure = RefreshFailure.NetworkFailure("offline"),
            cached = listOf(
                AccountBalance(
                    accountId = "acct",
                    label = "Primary",
                    totalBalance = "10",
                    currency = "USD",
                    isAvailable = true,
                    grantedBalance = "",
                    toppedUpBalance = "",
                    lastUpdated = 42L
                )
            ),
            markStale = {}
        )

        assertTrue(result.stale)
        assertEquals(42L, result.dataTimestamp)
    }

    @Test
    fun `stale projection reports non-stale when cache marker write fails`() {
        val result = RefreshRuntime.projectStaleFailure(
            accountId = "acct",
            failure = RefreshFailure.NetworkFailure("offline"),
            cached = listOf(
                AccountBalance(
                    accountId = "acct",
                    label = "Primary",
                    totalBalance = "10",
                    currency = "USD",
                    isAvailable = true,
                    grantedBalance = "",
                    toppedUpBalance = "",
                    lastUpdated = 42L
                )
            ),
            markStale = { error("injected marker failure") }
        )

        assertFalse(result.stale)
        assertNull(result.dataTimestamp)
        assertTrue(result.lastError == "offline")
    }
}
