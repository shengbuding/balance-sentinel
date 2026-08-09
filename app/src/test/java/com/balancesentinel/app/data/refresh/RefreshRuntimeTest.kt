package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.widget.AccountBalance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRuntimeTest {
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
