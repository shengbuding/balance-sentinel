package com.balancesentinel.app.work

import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRetryPlannerTest {
    @Test
    fun `partial refresh retries only retryable account failures`() {
        val planner = RefreshRetryPlanner(
            maxAttempts = 3,
            baseDelayMillis = 1_000L,
            maxDelayMillis = 2_500L,
            jitterMillis = { 250L }
        )

        val retryable = planner.next(
            accountId = "network-account",
            result = AccountRefreshResult.Failed(
                accountId = "network-account",
                failure = RefreshFailure.NetworkFailure("temporarily offline")
            ),
            previousAttempt = 0
        )
        val permanent = planner.next(
            accountId = "auth-account",
            result = AccountRefreshResult.Failed(
                accountId = "auth-account",
                failure = RefreshFailure.AuthenticationFailure("bad token")
            ),
            previousAttempt = 0
        )
        val success = planner.next(
            accountId = "successful-account",
            result = AccountRefreshResult.Committed("successful-account", balance = fakeBalance()),
            previousAttempt = 0
        )

        assertEquals(RetrySchedule("network-account", attempt = 1, delayMillis = 1_250L), retryable)
        assertNull(permanent)
        assertNull(success)
    }

    @Test
    fun `retry delay uses bounded exponential backoff and finite attempts`() {
        val planner = RefreshRetryPlanner(
            maxAttempts = 3,
            baseDelayMillis = 1_000L,
            maxDelayMillis = 2_500L,
            jitterMillis = { 900L }
        )
        val failure = AccountRefreshResult.Failed(
            accountId = "account",
            failure = RefreshFailure.RateLimited("try later")
        )

        assertEquals(1_900L, planner.next("account", failure, previousAttempt = 0)!!.delayMillis)
        assertEquals(2_500L, planner.next("account", failure, previousAttempt = 1)!!.delayMillis)
        assertEquals(2_500L, planner.next("account", failure, previousAttempt = 2)!!.delayMillis)
        assertNull(planner.next("account", failure, previousAttempt = 3))
    }

    private fun fakeBalance() =
        com.balancesentinel.app.data.api.UnifiedBalance(
            totalBalance = "1.00",
            currency = "USD",
            grantedBalance = "1.00",
            toppedUpBalance = "0.00",
            isAvailable = true
        )
}