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
        // consumed 逐对累加：100→80 (-20) + 80→60 (-20) = 40，toppedUp/granted 无正向跳变
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

    @Test
    fun `toppedUp detects mid-day top-up even when followed by consumption`() {
        // 模拟主钱包 7/5 场景：先消耗 → 充值 → 再消耗
        // last-first 公式会返回 0，但累加正向跳变应该捕获充值
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 100f),   // toppedUpBalance start=100
            RawRecord("acc1", 2000L, "CNY", 80f, 0f, 80f),      // 消耗 20，tu→80
            RawRecord("acc1", 3000L, "CNY", 180f, 0f, 180f),    // 充值 100，tu→180
            RawRecord("acc1", 4000L, "CNY", 150f, 0f, 150f),    // 消耗 30，tu→150
        )
        // last - first = 150-100 = 50，但实际只有一次充值 +100
        assertEquals(100f, RecordAggregator.computeToppedUp(sorted))
    }

    @Test
    fun `toppedUp accumulates multiple top-ups throughout the day`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 50f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 150f, 0f, 150f),   // +100
            RawRecord("acc1", 3000L, "CNY", 130f, 0f, 130f),   // 消耗 20
            RawRecord("acc1", 4000L, "CNY", 200f, 0f, 200f),   // +70
            RawRecord("acc1", 5000L, "CNY", 180f, 0f, 180f),   // 消耗 20
        )
        assertEquals(170f, RecordAggregator.computeToppedUp(sorted)) // 100+70
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

    // ── computeConsumed 测试（逐对增量法） ──

    @Test
    fun `consumed captures pure balance drop`() {
        // 余额从 100→60，toppedUp/granted 未变 → 纯消费 40
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", 2000L, "CNY", 60f, 0f, 100f)
        )
        assertEquals(40f, RecordAggregator.computeConsumed(sorted))
    }

    @Test
    fun `consumed skips pair where toppedUpBalance changed`() {
        // 充值区间：余额 50→100，toppedUpBalance 50→150 → 跳过，不计算消耗
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 50f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 100f, 0f, 150f)
        )
        assertEquals(0f, RecordAggregator.computeConsumed(sorted))
    }

    @Test
    fun `consumed skips pair where grantedBalance changed`() {
        // 赠送区间：grantedBalance 从 0→30 → 跳过
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 100f, 30f, 50f)
        )
        assertEquals(0f, RecordAggregator.computeConsumed(sorted))
    }

    @Test
    fun `consumed sums multiple pure consumption pairs`() {
        // 三笔记录：两段纯消费 10+20=30
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", 2000L, "CNY", 90f, 0f, 100f),   // -10
            RawRecord("acc1", 3000L, "CNY", 70f, 0f, 100f)    // -20
        )
        assertEquals(30f, RecordAggregator.computeConsumed(sorted))
    }

    @Test
    fun `consumed skips top-up pair`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 50f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 200f, 0f, 200f) // top-up: balance +150, toppedUpBalance +150
        )
        val consumed = RecordAggregator.computeConsumed(sorted)
        // 充值区间被跳过，没有纯消费
        assertEquals(0f, consumed)
    }

    @Test
    fun `consumed tracks pure balance drop between top-up and grant`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 90f, 0f, 50f)   // balance -10, no top/grant change → consumed
        )
        val consumed = RecordAggregator.computeConsumed(sorted)
        assertEquals(10f, consumed)
    }

    // ── 边界测试 ──

    @Test
    fun `toppedUp ignores jumps smaller than 1 point 0`() {
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", 2000L, "CNY", 100.5f, 0f, 50.8f) // diff=0.8, < 1.0 → ignored
        )
        assertEquals(0f, RecordAggregator.computeToppedUp(sorted))
    }

    @Test
    fun `toppedUp ignores non-integer jumps`() {
        // diff=8.83 is >= 1 but NOT near-integer → should be ignored
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 10f, 0f, 2.34f),
            RawRecord("acc1", 2000L, "CNY", 11.5f, 0f, 11.17f) // toppedUpDiff=8.83, not near-integer
        )
        assertEquals(0f, RecordAggregator.computeToppedUp(sorted))
    }

    @Test
    fun `toppedUp captures near-integer jump above point 99`() {
        // diff=10.005, frac=0.005 < 0.01 → near-integer → counted
        val sorted = listOf(
            RawRecord("acc1", 1000L, "CNY", 100f, 0f, 10f),
            RawRecord("acc1", 2000L, "CNY", 100f, 0f, 20.005f) // diff=10.005, near-integer
        )
        // float precision: 20.005f - 10f ≈ 10.005 ± epsilon
        assertEquals(10.005f, RecordAggregator.computeToppedUp(sorted), 0.01f)
    }

    @Test
    fun `aggregate includes grantedBalanceClose and toppedUpBalanceClose`() {
        val record = RawRecord("acc1", 1000L, "CNY", 100f, 5.5f, 94.5f)
        val result = RecordAggregator.aggregate(listOf(record), "2026-07-08")
        assertEquals(1, result.size)
        assertEquals(94.5f, result[0].toppedUpBalanceClose)
        assertEquals(5.5f, result[0].grantedBalanceClose)
    }

    @Test
    fun `aggregate handles multiple accounts with same currency`() {
        val now = 100000L
        val records = listOf(
            RawRecord("acc1", now, "CNY", 50f, 0f, 50f),
            RawRecord("acc1", now + 1, "CNY", 40f, 0f, 50f),
            RawRecord("acc2", now, "CNY", 200f, 10f, 190f),
            RawRecord("acc2", now + 1, "CNY", 180f, 10f, 190f)
        )
        val result = RecordAggregator.aggregate(records, "2026-07-08")
        assertEquals(2, result.size)
        val acc1 = result.find { it.accountId == "acc1" }!!
        val acc2 = result.find { it.accountId == "acc2" }!!
        assertEquals(10f, acc1.consumed)
        assertEquals(20f, acc2.consumed)
        assertEquals(0f, acc1.toppedUp) // toppedUpBalance unchanged
        assertEquals(0f, acc2.toppedUp)
    }
}
