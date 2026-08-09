package com.balancesentinel.app.work

import com.balancesentinel.app.data.refresh.AccountRefreshResult

/** Retry metadata for one account. */
data class RetrySchedule(
    val accountId: String,
    val attempt: Int,
    val delayMillis: Long
)

/** Test-first seam; production planning is introduced after the RED commit. */
class RefreshRetryPlanner(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 30_000L,
    val maxDelayMillis: Long = 15 * 60_000L,
    val jitterMillis: () -> Long = { 0L }
) {
    fun next(
        accountId: String,
        result: AccountRefreshResult,
        previousAttempt: Int
    ): RetrySchedule? = null
}