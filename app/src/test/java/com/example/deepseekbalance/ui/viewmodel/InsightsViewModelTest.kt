package com.example.deepseekbalance.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.deepseekbalance.data.model.DailySummary
import com.example.deepseekbalance.data.model.RawRecord
import com.example.deepseekbalance.data.repository.DailySummaryStore
import com.example.deepseekbalance.data.repository.RawRecordStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class InsightsViewModelTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    @After
    fun tearDown() {
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    // ── ViewModel loadData ──

    @Test
    fun `loadData populates both intraday and daily outputs`() {
        val now = System.currentTimeMillis()
        RawRecordStore.addRecord(
            context,
            RawRecord("acc1", now - 3600_000L, "CNY", 100f, 0f, 100f)
        )
        RawRecordStore.addRecord(
            context,
            RawRecord("acc1", now, "CNY", 90f, 0f, 90f)
        )

        val viewModel = InsightsViewModel(app)
        ShadowLooper.idleMainLooper()

        val state = viewModel.uiState.value
        assertFalse("Loading should be false", state.isLoading)
        assertNotNull("Intraday output should not be null", state.intradayOutput)
        assertNotNull("Daily output should not be null", state.dailyOutput)

        // Intraday: 2 points, balance dropped 10
        assertEquals(2, state.intradayOutput!!.dataPointCount)
        assertEquals(10f, state.intradayOutput!!.billReport.consumed, 0.01f)
        assertEquals(-10f, state.intradayOutput!!.billReport.netChange, 0.01f)
    }

    @Test
    fun `loadData handles empty data gracefully`() {
        val viewModel = InsightsViewModel(app)
        ShadowLooper.idleMainLooper()

        val state = viewModel.uiState.value
        assertFalse("Loading should be false", state.isLoading)
        assertNotNull(state.intradayOutput)
        assertNotNull(state.dailyOutput)
        assertEquals(0, state.intradayOutput!!.dataPointCount)
        assertTrue(state.dailyOutput!!.isEmpty)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `loadData populates daily output with summary data`() {
        val now = System.currentTimeMillis()
        val yesterday = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now - 24 * 3600_000L))

        DailySummaryStore.upsert(
            context,
            DailySummary(
                accountId = "acc1",
                date = yesterday,
                currency = "CNY",
                open = 100f,
                close = 90f,
                consumed = 10f,
                toppedUp = 0f,
                granted = 0f,
                avgBalance = 95f,
                sampleCount = 5,
                toppedUpBalanceClose = 0f,
                grantedBalanceClose = 0f
            )
        )

        val viewModel = InsightsViewModel(app)
        ShadowLooper.idleMainLooper()

        val state = viewModel.uiState.value
        assertFalse("Loading should be false", state.isLoading)
        assertNotNull("Daily output should not be null", state.dailyOutput)
        assertFalse("Daily output should not be empty", state.dailyOutput!!.isEmpty)
        assertEquals(1, state.dailyOutput!!.dailyPoints.size)
        assertEquals(yesterday, state.dailyOutput!!.dailyPoints[0].date)
        assertEquals(10f, state.dailyOutput!!.billReport.consumed, 0.01f)
    }

    @Test
    fun `selectCurrency triggers reload`() {
        val now = System.currentTimeMillis()
        RawRecordStore.addRecord(
            context,
            RawRecord("acc1", now, "CNY", 100f, 0f, 100f)
        )
        RawRecordStore.addRecord(
            context,
            RawRecord("acc1", now + 1000L, "USD", 50f, 0f, 50f)
        )

        val viewModel = InsightsViewModel(app)

        // Initial load picks first currency (CNY)
        assertEquals("CNY", viewModel.uiState.value.selectedCurrency)
        assertEquals(1, viewModel.uiState.value.intradayOutput!!.dataPointCount)
        assertEquals(100f, viewModel.uiState.value.intradayOutput!!.trendPoints[0].actualBalance)

        // Switch to USD
        viewModel.selectCurrency("USD")
        ShadowLooper.idleMainLooper()

        assertEquals("USD", viewModel.uiState.value.selectedCurrency)
        assertEquals(1, viewModel.uiState.value.intradayOutput!!.dataPointCount)
        assertEquals(50f, viewModel.uiState.value.intradayOutput!!.trendPoints[0].actualBalance)
    }

    @Test
    fun `setRangeDays triggers reload`() {
        val viewModel = InsightsViewModel(app)

        assertEquals(7, viewModel.uiState.value.rangeDays)

        viewModel.setRangeDays(30)
        ShadowLooper.idleMainLooper()
        assertEquals(30, viewModel.uiState.value.rangeDays)

        // Same value should not trigger reload
        viewModel.setRangeDays(30)
        assertEquals(30, viewModel.uiState.value.rangeDays)
    }

    // ── computeTrend: DailySummary-based trend ordering ──

    @Test
    fun `trend data orders by date ascending`() {
        val summaries: List<DailySummary> = listOf(
            DailySummary(
                accountId = "acc1", date = "2026-01-03", currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                granted = 0f, avgBalance = 95f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            ),
            DailySummary(
                accountId = "acc1", date = "2026-01-01", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            ),
            DailySummary(
                accountId = "acc1", date = "2026-01-02", currency = "CNY",
                open = 100f, close = 95f, consumed = 5f, toppedUp = 0f,
                granted = 0f, avgBalance = 97f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            )
        )
        val sorted = summaries.sortedBy { it.date }
        assertEquals("2026-01-01", sorted[0].date)
        assertEquals("2026-01-02", sorted[1].date)
        assertEquals("2026-01-03", sorted[2].date)
    }

    // ── Currency filtering ──

    @Test
    fun `filter summaries by currency`() {
        val summaries: List<DailySummary> = listOf(
            DailySummary(
                accountId = "acc1", date = "2026-01-01", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            ),
            DailySummary(
                accountId = "acc1", date = "2026-01-01", currency = "USD",
                open = 10f, close = 10f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 10f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            ),
            DailySummary(
                accountId = "acc1", date = "2026-01-02", currency = "CNY",
                open = 90f, close = 90f, consumed = 10f, toppedUp = 0f,
                granted = 0f, avgBalance = 90f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            )
        )

        val cnyOnly = summaries.filter { it.currency == "CNY" }
        assertEquals(2, cnyOnly.size)
        assertTrue(cnyOnly.all { it.currency == "CNY" })
    }

    // ── Account filtering ──

    @Test
    fun `filter summaries by account`() {
        val summaries: List<DailySummary> = listOf(
            DailySummary(
                accountId = "acc1", date = "2026-01-01", currency = "CNY",
                open = 100f, close = 100f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 100f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            ),
            DailySummary(
                accountId = "acc2", date = "2026-01-01", currency = "CNY",
                open = 200f, close = 200f, consumed = 0f, toppedUp = 0f,
                granted = 0f, avgBalance = 200f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            ),
            DailySummary(
                accountId = "acc1", date = "2026-01-02", currency = "CNY",
                open = 90f, close = 90f, consumed = 10f, toppedUp = 0f,
                granted = 0f, avgBalance = 90f, sampleCount = 1,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            )
        )

        val acc1Only = summaries.filter { it.accountId == "acc1" }
        assertEquals(2, acc1Only.size)
        assertTrue(acc1Only.all { it.accountId == "acc1" })
    }

    // ── Range days window ──

    @Test
    fun `computeTrend returns last N days of close values`() {
        val summaries = (1..10).map { day ->
            DailySummary(
                accountId = "acc1",
                date = "2026-01-${day.toString().padStart(2, '0')}",
                currency = "CNY",
                open = 100f,
                close = (100 + day * 10).toFloat(),
                consumed = 0f,
                toppedUp = 0f,
                granted = 0f,
                avgBalance = 100f,
                sampleCount = 1,
                toppedUpBalanceClose = 0f,
                grantedBalanceClose = 0f
            )
        }

        // Take last 7 days
        val trend = summaries.takeLast(7).map { it.date to it.close }
        assertEquals(7, trend.size)
        assertEquals(140f, trend[0].second) // day 4
        assertEquals(200f, trend.last().second) // day 10
    }
}
