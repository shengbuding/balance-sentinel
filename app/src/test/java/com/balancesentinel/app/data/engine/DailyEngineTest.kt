package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyEngineTest {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Test
    fun `empty summaries returns empty output`() {
        val input = DailyInput(emptyList(), emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertTrue(output.isEmpty)
        assertEquals(0, output.dailyPoints.size)
        assertEquals("最近7天", output.periodLabel)
    }

    @Test
    fun `single day summary produces one daily point`() {
        val summary = DailySummary(
            accountId = "acc1", date = "2026-07-04", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 5,
            toppedUpBalanceClose = 50f, grantedBalanceClose = 10f
        )
        val input = DailyInput(listOf(summary), emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertEquals(1, output.dailyPoints.size)
        assertEquals("2026-07-04", output.dailyPoints[0].date)
        assertEquals(90f, output.dailyPoints[0].balance)
        assertEquals(10f, output.dailyPoints[0].consumed, 0.01f)
        assertFalse(output.dailyPoints[0].isGapFill)
        assertEquals(10f, output.billReport.consumed, 0.01f)
    }

    @Test
    fun `multiple days aggregate correctly`() {
        val summaries = listOf(
            DailySummary("acc1", "2026-07-01", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 3, 0f, 0f),
            DailySummary("acc1", "2026-07-02", "CNY", 90f, 75f, 15f, 0f, 0f, 82f, 2, 0f, 0f),
            DailySummary("acc1", "2026-07-03", "CNY", 75f, 70f, 5f, 30f, 0f, 72f, 4, 30f, 0f)
        )
        val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertEquals(3, output.dailyPoints.size)
        assertEquals(30f, output.billReport.consumed, 0.01f)   // 10 + 15 + 5
        assertEquals(30f, output.billReport.toppedUp, 0.01f)   // 30 from 7/3
        assertEquals(0f, output.billReport.netChange, 0.01f)   // 30 - 30 = 0
    }

    @Test
    fun `gap-fill days are marked with isGapFill`() {
        val summaries = listOf(
            DailySummary("acc1", "2026-07-01", "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 0, 0f, 0f),
            DailySummary("acc1", "2026-07-04", "CNY", 100f, 80f, 20f, 0f, 0f, 90f, 5, 0f, 0f)
        )
        val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertEquals(2, output.dailyPoints.size)
        assertTrue(output.dailyPoints[0].isGapFill)   // sampleCount=0
        assertFalse(output.dailyPoints[1].isGapFill)   // sampleCount=5
    }

    @Test
    fun `rangeDays limits window`() {
        val summaries = (1..20).map { day ->
            val date = "2026-06-${(day + 10).toString().padStart(2, '0')}"
            DailySummary("acc1", date, "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f)
        }
        val input7 = DailyInput(summaries, emptyList(), "CNY", null, 7)
        assertEquals(7, DailyEngine.compute(input7).dailyPoints.size)

        val input30 = DailyInput(summaries, emptyList(), "CNY", null, 30)
        assertEquals(20, DailyEngine.compute(input30).dailyPoints.size)  // only 20 exist
    }

    @Test
    fun `depletion estimate with steady consumption`() {
        // Create 7 days with linearly increasing consumption: 5, 10, 15, 20, 25, 30, 35
        // This yields a slope of exactly 5.0 in linear regression on indices [0..6]
        val summaries = (1..7).map { day ->
            val date = "2026-07-${day.toString().padStart(2, '0')}"
            val consumed = 5f * day  // 5, 10, 15, 20, 25, 30, 35
            DailySummary("acc1", date, "CNY", 100f - day * 5f, 100f - (day - 1) * 5f,
                consumed, 0f, 0f, 100f - (day - 0.5f) * 5f, 3, 0f, 0f)
        }
        val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertNotNull(output.estimate)
        assertEquals(5f, output.estimate!!.dailyRate, 0.5f)
    }

    @Test
    fun `no estimate with insufficient data`() {
        val summaries = listOf(
            DailySummary("acc1", "2026-07-01", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 3, 0f, 0f)
        )
        val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertNull(output.estimate)  // only 1 data point with consumption
    }

    @Test
    fun `today raw records override today summary`() {
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date())
        val summary = DailySummary("acc1", today, "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 1, 0f, 0f)
        val todayRecords = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", now, "CNY", 75f, 0f, 75f)
        )
        val input = DailyInput(listOf(summary), todayRecords, "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertEquals(25f, output.dailyPoints[0].consumed, 0.01f)  // from raw records, not summary's 10f
        assertEquals(75f, output.dailyPoints[0].balance)
    }

    @Test
    fun `today point appears even without today summary in store`() {
        val now = System.currentTimeMillis()
        // Only historical summary, no today summary
        val yesterday = DailySummary("acc1", "2026-07-03", "CNY", 200f, 180f, 20f, 0f, 0f, 190f, 5, 0f, 0f)
        val todayRecords = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 180f, 0f, 180f),
            RawRecord("acc1", now, "CNY", 170f, 0f, 170f)
        )
        val input = DailyInput(listOf(yesterday), todayRecords, "CNY", null, 7)
        val output = DailyEngine.compute(input)

        // Should have both yesterday AND today
        assertTrue(output.dailyPoints.any { it.date == "2026-07-03" })
        val todayPoint = output.dailyPoints.find { !it.date.startsWith("2026-07-03") }
        assertNotNull("Today's point should be present", todayPoint)
        assertEquals(10f, todayPoint!!.consumed, 0.01f)
        assertEquals(170f, todayPoint.balance)
    }

    @Test
    fun `today toppedUp uses per-pair analysis on raw records`() {
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date())
        val summary = DailySummary("acc1", today, "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f)
        // toppedUpBalance increased by 30 (integer, >=1) between records
        val todayRecords = listOf(
            RawRecord("acc1", now - 2000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now - 1000L, "CNY", 95f, 0f, 50f),   // consumed 5
            RawRecord("acc1", now, "CNY", 125f, 0f, 80f)           // toppedUp +30, balance +30
        )
        val input = DailyInput(listOf(summary), todayRecords, "CNY", null, 7)
        val output = DailyEngine.compute(input)

        val todayPoint = output.dailyPoints.find { it.date == today }!!
        assertEquals(30f, todayPoint.toppedUp, 0.01f)  // per-pair detected
    }

    @Test
    fun `today grant uses per-pair analysis on raw records`() {
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date())
        val summary = DailySummary("acc1", today, "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f)
        // grantedBalance increased by 10 between records
        val todayRecords = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now, "CNY", 110f, 10f, 50f)  // granted +10
        )
        val input = DailyInput(listOf(summary), todayRecords, "CNY", null, 7)
        val output = DailyEngine.compute(input)

        val todayPoint = output.dailyPoints.find { it.date == today }!!
        assertEquals(10f, todayPoint.granted, 0.01f)  // per-pair detected
    }

    @Test
    fun `daily point includes open and sampleCount from summary`() {
        val summary = DailySummary(
            accountId = "acc1", date = "2026-07-04", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 5,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        )
        val input = DailyInput(listOf(summary), emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        val point = output.dailyPoints[0]
        assertEquals(100f, point.open, 0.01f)
        assertEquals(5, point.sampleCount)
    }

    @Test
    fun `today point includes open and sampleCount from raw records`() {
        val now = System.currentTimeMillis()
        val todayRecords = listOf(
            RawRecord("acc1", now - 2000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", now - 1000L, "CNY", 95f, 0f, 95f),
            RawRecord("acc1", now, "CNY", 90f, 0f, 90f)
        )
        val input = DailyInput(emptyList(), todayRecords, "CNY", null, 7)
        val output = DailyEngine.compute(input)

        val point = output.dailyPoints[0]
        assertEquals(100f, point.open, 0.01f)    // first record's totalBalance
        assertEquals(3, point.sampleCount)        // 3 records
    }
}
