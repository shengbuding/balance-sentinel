package com.balancesentinel.app.work

import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import kotlin.math.min
import kotlin.random.Random

/** Retry metadata for one account. */
data class RetrySchedule(
    val accountId: String,
    val attempt: Int,
    val delayMillis: Long
)

/** Calculates bounded retries for recoverable per-account failures. */
class RefreshRetryPlanner(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    val jitterMillis: () -> Long = {
        Random.nextLong(0L, (baseDelayMillis / 4L).coerceAtLeast(1L) + 1L)
    }
) {
    fun next(
        accountId: String,
        result: AccountRefreshResult,
        previousAttempt: Int
    ): RetrySchedule? {
        val failure = (result as? AccountRefreshResult.Failed)?.failure ?: return null
        if (!failure.retryable || previousAttempt >= maxAttempts || maxAttempts <= 0) return null

        val exponent = previousAttempt.coerceIn(0, 62)
        val exponential = safeMultiply(baseDelayMillis.coerceAtLeast(0L), 1L shl exponent)
        val jitter = jitterMillis().coerceAtLeast(0L)
        val delay = min(maxDelayMillis.coerceAtLeast(0L), safeAdd(exponential, jitter))
        return RetrySchedule(
            accountId = accountId,
            attempt = previousAttempt + 1,
            delayMillis = delay
        )
    }

    private fun safeMultiply(value: Long, multiplier: Long): Long =
        if (value == 0L || multiplier == 0L) 0L
        else if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE
        else value * multiplier

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BASE_DELAY_MILLIS = 30_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 15 * 60_000L
    }
}