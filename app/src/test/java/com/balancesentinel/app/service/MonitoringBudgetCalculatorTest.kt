package com.balancesentinel.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringBudgetCalculatorTest {
    @Test
    fun `rolling window clips open and closed sessions and merges overlap adjacency`() {
        val now = 100_000L
        val intervals = listOf(
            MonitoringBudgetInterval(70_000L, 80_000L),
            MonitoringBudgetInterval(79_000L, 90_000L),
            MonitoringBudgetInterval(90_000L, null),
            MonitoringBudgetInterval(20_000L, 60_000L)
        )

        assertEquals(30_000L, MonitoringBudgetCalculator.usedMillis(intervals, now, null, 30_000L))
    }

    @Test
    fun `reset is bounded by now and does not create second budget`() {
        val now = 100_000L
        val intervals = listOf(MonitoringBudgetInterval(1L, 99_000L))

        assertEquals(95_000L, MonitoringBudgetCalculator.effectiveCutoff(now, 30_000L, 95_000L))
        assertEquals(4_000L, MonitoringBudgetCalculator.usedMillis(intervals, now, 95_000L, 30_000L))
    }

    @Test
    fun `exact cutoff end and now start contribute no duration`() {
        val now = 100_000L
        val intervals = listOf(
            MonitoringBudgetInterval(70_000L, 70_000L),
            MonitoringBudgetInterval(now, null),
            MonitoringBudgetInterval(80_000L, 90_000L)
        )

        assertEquals(10_000L, MonitoringBudgetCalculator.usedMillis(intervals, now, null, 30_000L))
    }
}
