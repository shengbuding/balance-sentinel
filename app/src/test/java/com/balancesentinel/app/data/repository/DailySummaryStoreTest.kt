package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DailySummaryStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DailySummaryStore.clear(context)
    }

    @After
    fun tearDown() {
        DailySummaryStore.clear(context)
    }

    // ── Add & Get ──

    @Test
    fun `add and retrieve single summary`() {
        val summary = DailySummary(
            accountId = "acc1", date = "2026-01-15", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            avgBalance = 95f, sampleCount = 5
        )
        DailySummaryStore.addSummary(context, summary)
        val all = DailySummaryStore.getSummaries(context)
        assertEquals(1, all.size)
        assertEquals("CNY", all[0].currency)
        assertEquals(100f, all[0].open)
        assertEquals(90f, all[0].close)
    }

    @Test
    fun `add same date+currency+accountId is ignored (immutable)`() {
        val s1 = DailySummary(
            accountId = "acc1", date = "2026-01-15", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            avgBalance = 95f, sampleCount = 5
        )
        val s2 = DailySummary(
            accountId = "acc1", date = "2026-01-15", currency = "CNY",
            open = 100f, close = 80f, consumed = 20f, toppedUp = 0f,
            avgBalance = 90f, sampleCount = 10
        )
        DailySummaryStore.addSummary(context, s1)
        DailySummaryStore.addSummary(context, s2)  // 第二次写入被忽略
        val all = DailySummaryStore.getSummaries(context)
        assertEquals(1, all.size)
        assertEquals(90f, all[0].close)     // 保留第一次的值
        assertEquals(5, all[0].sampleCount) // 未被第二次覆盖
    }

    @Test
    fun `different dates create separate entries`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 3)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-16", currency = "CNY",
                open = 90f, close = 80f, consumed = 10f, toppedUp = 0f,
                avgBalance = 85f, sampleCount = 4)
        )
        val all = DailySummaryStore.getSummaries(context)
        assertEquals(2, all.size)
    }

    @Test
    fun `different currencies create separate entries`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 2)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "USD",
                open = 50f, close = 45f, consumed = 5f, toppedUp = 0f,
                avgBalance = 47f, sampleCount = 2)
        )
        val all = DailySummaryStore.getSummaries(context)
        assertEquals(2, all.size)
    }

    @Test
    fun `different accountIds create separate entries`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 2)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc2", date = "2026-01-15", currency = "CNY",
                open = 200f, close = 180f, consumed = 20f, toppedUp = 0f,
                avgBalance = 190f, sampleCount = 2)
        )
        assertEquals(2, DailySummaryStore.getSummaries(context).size)
    }

    @Test
    fun `summaries are sorted by date ascending`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-20", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                avgBalance = 100f, sampleCount = 1)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-10", currency = "CNY",
                open = 50f, close = 50f, consumed = 0f, toppedUp = 0f,
                avgBalance = 50f, sampleCount = 1)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 75f, close = 75f, consumed = 0f, toppedUp = 0f,
                avgBalance = 75f, sampleCount = 1)
        )
        val all = DailySummaryStore.getSummaries(context)
        assertEquals("2026-01-10", all[0].date)
        assertEquals("2026-01-15", all[1].date)
        assertEquals("2026-01-20", all[2].date)
    }

    // ── Filtering ──

    @Test
    fun `getSummariesForCurrency filters correctly`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 3)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "USD",
                open = 50f, close = 45f, consumed = 5f, toppedUp = 0f,
                avgBalance = 47f, sampleCount = 2)
        )
        val cnyOnly = DailySummaryStore.getSummariesForCurrency(context, "CNY")
        assertEquals(1, cnyOnly.size)
        assertEquals("CNY", cnyOnly[0].currency)
    }

    @Test
    fun `getSummariesForAccount filters correctly`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 2)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc2", date = "2026-01-15", currency = "CNY",
                open = 200f, close = 180f, consumed = 20f, toppedUp = 0f,
                avgBalance = 190f, sampleCount = 2)
        )
        val acc1Only = DailySummaryStore.getSummariesForAccount(context, "acc1")
        assertEquals(1, acc1Only.size)
        assertEquals("acc1", acc1Only[0].accountId)
    }

    @Test
    fun `getAvailableCurrencies returns distinct currencies`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 1)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-16", currency = "CNY",
                open = 90f, close = 80f, consumed = 10f, toppedUp = 0f,
                avgBalance = 85f, sampleCount = 1)
        )
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "USD",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
                avgBalance = 10f, sampleCount = 1)
        )
        val currencies = DailySummaryStore.getAvailableCurrencies(context)
        assertEquals(2, currencies.size)
        assertTrue(currencies.contains("CNY"))
        assertTrue(currencies.contains("USD"))
    }

    // ── Empty state ──

    @Test
    fun `getSummaries returns empty list when store is empty`() {
        val all = DailySummaryStore.getSummaries(context)
        assertTrue(all.isEmpty())
    }

    @Test
    fun `clear removes all summaries`() {
        DailySummaryStore.addSummary(
            context,
            DailySummary(accountId = "acc1", date = "2026-01-15", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                avgBalance = 95f, sampleCount = 3)
        )
        DailySummaryStore.clear(context)
        assertTrue(DailySummaryStore.getSummaries(context).isEmpty())
    }

    // ── Aggregation ──

    @Test
    fun `aggregateAndSave with empty list does nothing`() {
        // aggregateAndSave removed; test inline path
        assertTrue(com.balancesentinel.app.data.engine.RecordAggregator.aggregate(emptyList(), "2026-01-01").isEmpty())
        assertTrue(DailySummaryStore.getSummaries(context).isEmpty())
    }

    @Test
    fun `aggregateAndSave computes consumed from raw records`() {
        val records = listOf(
            RawRecord("acc1", 1000, "CNY", 100f, 10f, 90f),
            RawRecord("acc1", 2000, "CNY", 80f, 10f, 70f),
            RawRecord("acc1", 3000, "CNY", 60f, 10f, 50f)
        )
        val summaryDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(records.first().timestamp))
        val aggregated = com.balancesentinel.app.data.engine.RecordAggregator.aggregate(records, summaryDate)
        for (summary in aggregated) {
            DailySummaryStore.addSummary(context, summary)
        }

        val summaries = DailySummaryStore.getSummaries(context)
        assertEquals(1, summaries.size)
        assertEquals(100f, summaries[0].open)
        assertEquals(60f, summaries[0].close)
        // consumed = open - close + toppedUp + granted = 100-60+0+0 = 40
        assertEquals(40f, summaries[0].consumed)
        assertEquals(3, summaries[0].sampleCount)
    }

    @Test
    fun `aggregateAndSave computes toppedUp from API values`() {
        val records = listOf(
            RawRecord("acc1", 1000, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", 2000, "CNY", 200f, 0f, 200f)
        )
        val summaryDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(records.first().timestamp))
        val aggregated = com.balancesentinel.app.data.engine.RecordAggregator.aggregate(records, summaryDate)
        for (summary in aggregated) {
            DailySummaryStore.addSummary(context, summary)
        }

        val summaries = DailySummaryStore.getSummaries(context)
        assertEquals(1, summaries.size)
        // toppedUp = last.toppedUpBalance - first.toppedUpBalance = 200 - 100 = 100
        assertEquals(100f, summaries[0].toppedUp)
        // consumed = open-close+toppedUp+granted = 100-200+100+0 = 0
        assertEquals(0f, summaries[0].consumed)
    }

    @Test
    fun `aggregateAndSave groups by currency and account`() {
        val records = listOf(
            RawRecord("acc1", 1000, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", 2000, "CNY", 90f, 0f, 90f),
            RawRecord("acc1", 1000, "USD", 50f, 0f, 50f),
            RawRecord("acc1", 2000, "USD", 40f, 0f, 40f),
            RawRecord("acc2", 1000, "CNY", 200f, 0f, 200f),
            RawRecord("acc2", 2000, "CNY", 180f, 0f, 180f)
        )
        val summaryDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(records.first().timestamp))
        val aggregated = com.balancesentinel.app.data.engine.RecordAggregator.aggregate(records, summaryDate)
        for (summary in aggregated) {
            DailySummaryStore.addSummary(context, summary)
        }

        val all = DailySummaryStore.getSummaries(context)
        assertEquals(3, all.size) // CNY-acc1, USD-acc1, CNY-acc2
    }

    @Test
    fun `aggregateAndSave uses records timestamp for date, not current time`() {
        // 模拟午夜聚合场景：当前时间是新的一天，但记录属于昨天
        // Robolectric 默认 System.currentTimeMillis() = 0 (1970-01-01)
        // 记录时间戳 = 2001-09-09 12:00:00 UTC
        val recordDate = 1000000000000L // 2001-09-09
        val records = listOf(
            RawRecord("acc1", recordDate, "CNY", 100f, 10f, 90f),
            RawRecord("acc1", recordDate + 3600_000L, "CNY", 90f, 10f, 80f)
        )
        val summaryDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(records.first().timestamp))
        val aggregated = com.balancesentinel.app.data.engine.RecordAggregator.aggregate(records, summaryDate)
        for (summary in aggregated) {
            DailySummaryStore.addSummary(context, summary)
        }

        val summaries = DailySummaryStore.getSummaries(context)
        assertEquals(1, summaries.size)

        // 关键断言：摘要日期必须匹配记录的日期，而非 Date() (即 Robolectric 的 1970-01-01)
        val expectedDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(recordDate))
        assertEquals(expectedDate, summaries[0].date)
        // 2001-09-09 ≠ 1970-01-01（旧代码的 bug）
        assertNotEquals("1970-01-01", summaries[0].date)
    }

    // ── upsert ──

    @Test
    fun `upsert inserts new summary`() {
        val summary = DailySummary(
            accountId = "acc1", date = "2026-07-04", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 5,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        )
        DailySummaryStore.upsert(context, summary)

        val all = DailySummaryStore.getSummaries(context)
        assertEquals(1, all.size)
        assertEquals("2026-07-04", all[0].date)
    }

    @Test
    fun `upsert same key is ignored (immutable)`() {
        val s1 = DailySummary(
            accountId = "acc1", date = "2026-07-04", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 5,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        )
        DailySummaryStore.upsert(context, s1)

        val s2 = DailySummary(
            accountId = "acc1", date = "2026-07-04", currency = "CNY",
            open = 100f, close = 80f, consumed = 20f, toppedUp = 0f,
            granted = 0f, avgBalance = 90f, sampleCount = 8,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        )
        DailySummaryStore.upsert(context, s2)  // 第二次写入被忽略

        val all = DailySummaryStore.getSummaries(context)
        assertEquals(1, all.size)
        assertEquals(10f, all[0].consumed, 0.01f)  // 保留第一次的值
        assertEquals(5, all[0].sampleCount)          // 未被第二次覆盖
    }

    // ── Range query ──

    @Test
    fun `getSummariesInRange filters correctly`() {
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-01", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-03", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-05", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )

        val range = DailySummaryStore.getSummariesInRange(context, "2026-07-02", "2026-07-04")
        assertEquals(1, range.size)
        assertEquals("2026-07-03", range[0].date)
    }

    // ── hasSummaryForDate ──

    @Test
    fun `hasSummaryForDate returns true only when summary exists`() {
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-04", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )

        assertTrue(DailySummaryStore.hasSummaryForDate(context, "2026-07-04", "CNY", "acc1"))
        assertFalse(DailySummaryStore.hasSummaryForDate(context, "2026-07-05", "CNY", "acc1"))
    }

    // ── ensureContinuity ──

    @Test
    fun `ensureContinuity fills gaps between two summaries`() {
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-01", currency = "CNY",
                open = 100f, close = 95f, consumed = 5f, toppedUp = 0f,
                granted = 0f, avgBalance = 97f, sampleCount = 3,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )
        // Gap: 7/2, 7/3 missing
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-04", currency = "CNY",
                open = 95f, close = 85f, consumed = 10f, toppedUp = 0f,
                granted = 0f, avgBalance = 90f, sampleCount = 4,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )

        DailySummaryStore.ensureContinuity(context, "2026-07-01", "2026-07-03")

        val all = DailySummaryStore.getSummaries(context).sortedBy { it.date }
        assertTrue(all.any { it.date == "2026-07-02" && it.sampleCount == 0 })
        assertTrue(all.any { it.date == "2026-07-03" && it.sampleCount == 0 })
        assertEquals(95f, all.find { it.date == "2026-07-02" }!!.close)  // carryBalance
    }

    @Test
    fun `ensureContinuity does not fill today`() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        DailySummaryStore.upsert(
            context,
            DailySummary(accountId = "acc1", date = "2026-07-01", currency = "CNY",
                open = 100f, close = 95f, consumed = 5f, toppedUp = 0f,
                granted = 0f, avgBalance = 97f, sampleCount = 3,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f)
        )

        DailySummaryStore.ensureContinuity(context, "2026-07-01", today)

        val all = DailySummaryStore.getSummaries(context)
        assertFalse(all.any { it.date == today && it.sampleCount == 0 })
    }
}
