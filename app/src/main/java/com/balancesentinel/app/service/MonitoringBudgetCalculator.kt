package com.balancesentinel.app.service

/** A half-open monitoring session. A null end means the session is still open. */
data class MonitoringBudgetInterval(val startedAt: Long, val endedAt: Long?)

/** Pure rolling-window union calculator for Android dataSync foreground budget. */
object MonitoringBudgetCalculator {
    const val DEFAULT_WINDOW_MILLIS: Long = 86_400_000L

    fun effectiveCutoff(
        now: Long,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
        lastUserForegroundResetAt: Long? = null
    ): Long = minOf(
        now,
        maxOf(now - windowMillis, lastUserForegroundResetAt ?: Long.MIN_VALUE)
    )

    fun usedMillis(
        intervals: Iterable<MonitoringBudgetInterval>,
        now: Long,
        lastUserForegroundResetAt: Long? = null,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS
    ): Long = 0L

    fun remainingMillis(
        intervals: Iterable<MonitoringBudgetInterval>,
        now: Long,
        budgetMillis: Long,
        lastUserForegroundResetAt: Long? = null,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS
    ): Long = (budgetMillis - usedMillis(intervals, now, lastUserForegroundResetAt, windowMillis)).coerceAtLeast(0L)
}
