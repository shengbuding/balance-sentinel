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
    ): Long {
        val cutoff = effectiveCutoff(now, windowMillis, lastUserForegroundResetAt)
        if (now <= cutoff) return 0L
        val clipped = intervals.asSequence()
            .map { interval ->
                val start = maxOf(interval.startedAt, cutoff)
                val end = minOf(interval.endedAt ?: now, now)
                start to end
            }
            .filter { (start, end) -> end > start }
            .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
            .toList()
        if (clipped.isEmpty()) return 0L

        var mergedStart = clipped.first().first
        var mergedEnd = clipped.first().second
        var total = 0L
        for (index in 1 until clipped.size) {
            val (start, end) = clipped[index]
            if (start <= mergedEnd) {
                if (end > mergedEnd) mergedEnd = end
            } else {
                total += mergedEnd - mergedStart
                mergedStart = start
                mergedEnd = end
            }
        }
        return total + (mergedEnd - mergedStart)
    }

    fun remainingMillis(
        intervals: Iterable<MonitoringBudgetInterval>,
        now: Long,
        budgetMillis: Long,
        lastUserForegroundResetAt: Long? = null,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS
    ): Long = (budgetMillis - usedMillis(intervals, now, lastUserForegroundResetAt, windowMillis)).coerceAtLeast(0L)
}
