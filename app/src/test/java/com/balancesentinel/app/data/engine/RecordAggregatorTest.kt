package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import org.junit.Assert.*
import org.junit.Test

/**
 * RecordAggregator 纯 JVM 单元测试。
 * 验证聚合公式的单一真相源。
 */
class RecordAggregatorTest {

    // ── 聚合 identity 测试 ──

    @Test
    fun `empty records returns empty list`() {
        val result = RecordAggregator.aggregate(emptyList(), "2026-07-04")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single record maps to DailySummary`() {
        val record = RawRecord("acc1", 1000L, "CNY", 100f, 5f, 95f)
        val result = RecordAggregator.aggregate(listOf(record), "2026-07-04")

        assertEquals(1, result.size)
        assertEquals("acc1", result[0].accountId)
        assertEquals("2026-07-04", result[0].date)
        assertEquals("CNY", result[0].currency)
        assertEquals(100f, result[0].open)
        assertEquals(100f, result[0].close)
        assertEquals(0f, result[0].toppedUp)
        assertEquals(0f, result[0].granted)
        assertEquals(0f, result[0].consumed)
        assertEquals(100f, result[0].avgBalance)
        assertEquals(1, result[0].sampleCount)
    }

    @Test
    fun `multiple records same currency+account aggregate correctly`() {
        val now = 100000L
        val records = listOf(
            RawRecord("acc1", now, "CNY", 100f, 10f, 90f),
            RawRecord("acc1", now + 1000, "CNY", 80f, 10f, 70f),
            RawRecord("acc1", now + 2000, "CNY", 60f, 10f, 50f)
        )
        val result = RecordAggregator.aggregate(records, "2026-07-04")

        assertEquals(1, result.size)
        assertEquals(100f, result[0].open)
        assertEquals(60f, result[0].close)
        // consumed = open - close + toppedUp + granted = 100-60+0+0 = 40
        assertEquals(40f, result[0].consumed)
        assertEquals(3, result[0].sampleCount)
    }

    @Test
    fun `aggregate groups by currency and account`() {
        val now = 100000L
        val records = listOf(
            RawRecord("acc1", now, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", now, "CNY", 90f, 0f, 90f),
            RawRecord("acc1", now, "USD", 50f, 0f, 50f),
            RawRecord("acc1", now, "USD", 40f, 0f, 40f),
            RawRecord("acc2", now, "CNY", 200f, 0f, 200f),
            RawRecord("acc2", now, "CNY", 180f, 0f, 180f)
        )
        val result = RecordAggregator.aggregate(records, "2026-07-04")
        assertEquals(3, result.size) // CNY-acc1, USD-acc1, CNY-acc2
    }

    // ── computeToppedUp 测试 ──

    @Test
    fun `toppedUp detects increase correctly`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 90f),  // toppedUpBalance = 90
            RawRecord("acc1", 2000L, "CNY", 200f, 0f, 200f)   // toppedUpBalance = 200
        )
        assertEquals(110f, RecordAggregator.computeToppedUp(sorted))
    }

    @Test
    fun `toppedUp returns zero when balance decreases`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 200f, 0f, 200f),
            RawRecord("acc1", 2000L, "CNY", 100f, 0f, 100f)
        )
        assertEquals(0f, RecordAggregator.computeToppedUp(sorted))
    }

    @Test
    fun `toppedUp returns zero when unchanged`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 100f, 0f, 50f)
        )
        assertEquals(0f, RecordAggregator.computeToppedUp(sorted))
    }

    // ── computeGranted 测试 ──

    @Test
    fun `granted detects increase correctly`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 10f, 90f),
            RawRecord("acc1", 2000L, "CNY", 100f, 50f, 50f)
        )
        assertEquals(40f, RecordAggregator.computeGranted(sorted))
    }

    @Test
    fun `granted returns zero when balance decreases`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 50f, 50f),
            RawRecord("acc1", 2000L, "CNY", 100f, 10f, 90f)
        )
        assertEquals(0f, RecordAggregator.computeGranted(sorted))
    }

    // ── computeConsumed 测试（accounting identity） ──

    @Test
    fun `consumed follows accounting identity`() {
        // open=100, close=60, toppedUp=0, granted=0 → consumed = 40
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", 2000L, "CNY", 60f, 0f, 60f)
        )
        assertEquals(40f, RecordAggregator.computeConsumed(sorted, 0f, 0f))
    }

    @Test
    fun `consumed with top-up does not double-count`() {
        // 用户充值50，余额从50→100，实际消耗 = open-close+toppedUp = 50-100+50 = 0
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 50f, 0f, 0f),
            RawRecord("acc1", 2000L, "CNY", 100f, 0f, 0f)
        )
        // toppedUp change: 100-0 = 100 (API toppedUpBalance reflects accumulated top-ups)
        val toppedUp = RecordAggregator.computeToppedUp(
            listOf(
                RawRecord("acc1", 1000L, "CNY", 50f, 0f, 50f),
                RawRecord("acc1", 2000L, "CNY", 100f, 0f, 150f)
            )
        )
        assertEquals(100f, toppedUp)
        val consumed = RecordAggregator.computeConsumed(
            listOf(
                RawRecord("acc1", 1000L, "CNY", 50f, 0f, 50f),
                RawRecord("acc1", 2000L, "CNY", 100f, 0f, 150f)
            ),
            toppedUp, 0f
        )
        // 50-100+100+0 = 50 ≠ 0 → wait, that means 50 was consumed
        // Actually, if they topped up 100 and balance only went from 50→100, they consumed 50
        assertEquals(50f, consumed)
    }

    @Test
    fun `consumed with both top-up and grant`() {
        // Combined scenario: toppedUp=50, granted=30
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 100f, 0f, 100f)
        )
        val toppedUp = RecordAggregator.computeToppedUp(
            listOf(
                RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
                RawRecord("acc1", 2000L, "CNY", 100f, 0f, 100f)
            )
        )
        assertEquals(50f, toppedUp)

        val granted = RecordAggregator.computeGranted(
            listOf(
                RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
                RawRecord("acc1", 2000L, "CNY", 100f, 30f, 100f)
            )
        )
        assertEquals(30f, granted)

        val consumed = RecordAggregator.computeConsumed(
            listOf(
                RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
                RawRecord("acc1", 2000L, "CNY", 100f, 30f, 100f)
            ),
            toppedUp, granted
        )
        // 100-100+50+30 = 80
        assertEquals(80f, consumed)
    }

    @Test
    fun `consumed is never negative`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 50f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 200f, 0f, 200f) // huge top-up
        )
        val toppedUp = RecordAggregator.computeToppedUp(sorted)
        val consumed = RecordAggregator.computeConsumed(sorted, toppedUp, 0f)
        // 50-200+150 = 0, coerceAtLeast(0) = 0
        assertEquals(0f, consumed)
        assertTrue("consumed should be >= 0, was $consumed", consumed >= 0f)
    }
}
