package com.balancesentinel.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuotaModelsTest {
    @Test
    fun `reset timestamps accept iso seconds and epoch millis`() {
        assertEquals(1_787_184_000_000L, quotaResetEpochMillis("2026-08-20T00:00:00Z"))
        assertEquals(1_787_184_000_000L, quotaResetEpochMillis("1787184000"))
        assertEquals(1_787_184_000_000L, quotaResetEpochMillis("1787184000000"))
    }

    @Test
    fun `custom windows are matched by exact id instead of shared fallback rank`() {
        val snapshot = QuotaSnapshot(
            listOf(
                QuotaPeriodSnapshot("alpha", 10.0, 90.0),
                QuotaPeriodSnapshot("beta", 20.0, 80.0)
            )
        )

        assertEquals(80.0, snapshot.find("beta")?.remainingPercent ?: -1.0, 0.0)
        assertNull(snapshot.find("gamma"))
    }

    @Test
    fun `known aliases retain stable order`() {
        val snapshot = QuotaSnapshot(
            listOf(
                QuotaPeriodSnapshot("monthly", 30.0, 70.0),
                QuotaPeriodSnapshot("rolling_5h", 10.0, 90.0),
                QuotaPeriodSnapshot("weekly", 20.0, 80.0)
            )
        )
        assertEquals(
            listOf("rolling_5h", "weekly", "monthly"),
            snapshot.ordered().map { it.id }
        )
    }
}
