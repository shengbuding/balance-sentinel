package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.engine.DailyBillReport
import com.balancesentinel.app.data.engine.DailyOutput
import com.balancesentinel.app.data.engine.DailyPoint
import com.balancesentinel.app.data.engine.DepletionEstimate
import com.balancesentinel.app.data.engine.EstimateMethod
import com.balancesentinel.app.data.engine.IntradayBillReport
import com.balancesentinel.app.data.engine.IntradayOutput
import com.balancesentinel.app.data.engine.IntradayPoint
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.RoomHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InsightsViewModelTest {

    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var database: WalletDatabase

    /**
     * Wait for the ViewModel's async coroutine (Dispatchers.Default) to settle.
     * awaitViewModel(viewModel) only drains the main thread; the production
     * code changed to launch(Dispatchers.Default) at 103587b, so tests must
     * poll the StateFlow instead.
     */
    private fun awaitViewModel(viewModel: InsightsViewModel) {
        val deadline = System.currentTimeMillis() + 5000
        while (viewModel.uiState.value.isLoading && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        // Give the final state-copy write time to propagate
        Thread.sleep(50)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        WalletDatabaseProvider.installForTests(database)
        runBlocking { database.accountDao().insertCreate(insightsRoomAccount()) }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        WalletDatabaseProvider.clearForTests()
        Dispatchers.resetMain()
    }

    // ── ViewModel loadData ──

    @Test
    fun `loadData populates both intraday and daily outputs`() {
        val now = System.currentTimeMillis()
        addInsightsRoomRecords(
            RawRecord(INSIGHTS_ACCOUNT_ID, now - 3600_000L, "CNY", 100f, 0f, 100f),
            RawRecord(INSIGHTS_ACCOUNT_ID, now, "CNY", 90f, 0f, 90f)
        )

        val viewModel = createViewModel()
        awaitViewModel(viewModel)

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
        val viewModel = createViewModel()
        awaitViewModel(viewModel)

        val state = viewModel.uiState.value
        assertFalse("Loading should be false", state.isLoading)
        assertNotNull(state.intradayOutput)
        assertNotNull(state.dailyOutput)
        assertEquals(0, state.intradayOutput!!.dataPointCount)
        assertTrue(state.dailyOutput!!.isEmpty)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `loadData does not present credential corruption as empty account data`() {
        // Mutation caught: converting AccountLoadState.Corrupt to an empty account list.
        val corruption = DataCorruptionException("repository payload corrupt")
        val source = RecordingAccountUiRepository(AccountLoadState.Corrupt(corruption))
        val viewModel = InsightsViewModel(app, source)
        awaitAccountState(viewModel) { state ->
            state.accountLoadState is AccountLoadState.Corrupt &&
                !state.isLoading &&
                state.intradayOutput != null &&
                state.dailyOutput != null
        }

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue("Corruption must remain typed in InsightsUiState", state.accountLoadState is AccountLoadState.Corrupt)
        assertSame(corruption, (state.accountLoadState as AccountLoadState.Corrupt).error)
        assertTrue(state.credentialCorrupt)
        assertNotNull("Credential corruption must remain explicit in the UI state", state.intradayOutput)
        assertNotNull("Credential corruption must remain explicit in the UI state", state.dailyOutput)
    }

    @Test
    fun `account flow replaces instances while preserving selection by stable id`() {
        // Mutation caught: holding an AccountInfo object or reselecting by list position after Flow emission.
        val first = AccountInfo(
            id = "00fd859d-2119-40a0-9477-bda4fa12a2a4",
            label = "Before",
            apiKey = "sk-before-insights",
            revision = 4
        )
        val source = RecordingAccountUiRepository(AccountLoadState.Ready(listOf(first)))
        val viewModel = InsightsViewModel(app, source)
        awaitAccountState(viewModel) { it.accounts.singleOrNull()?.label == "Before" }
        viewModel.selectAccount(first.id)
        val replacement = first.copy(label = "After", apiKey = "sk-after-insights", revision = 5)

        source.publish(AccountLoadState.Ready(listOf(replacement)))
        awaitAccountState(viewModel) { it.accounts.singleOrNull()?.label == "After" }

        val state = viewModel.uiState.value
        assertEquals(first.id, state.selectedAccountId)
        assertSame(replacement, state.accounts.single())
        assertNotSame(first, state.accounts.single())
        assertEquals(1, source.subscriptionCount)
    }

    @Test
    fun `corruption after a valid emission preserves last accounts but disables ordinary state`() {
        // Mutation caught: replacing a previously rendered account snapshot with emptyList on corruption.
        val account = AccountInfo(
            id = "626b9fc2-98a8-44f9-ae27-bc49d72eef45",
            label = "Retained",
            apiKey = "sk-retained-insights"
        )
        val source = RecordingAccountUiRepository(AccountLoadState.Ready(listOf(account)))
        val viewModel = InsightsViewModel(app, source)
        awaitAccountState(viewModel) { it.accounts == listOf(account) }

        source.publish(AccountLoadState.Corrupt(DataCorruptionException("became corrupt")))
        awaitAccountState(viewModel) { it.accountLoadState is AccountLoadState.Corrupt }

        assertEquals(listOf(account), viewModel.uiState.value.accounts)
        assertTrue(viewModel.uiState.value.credentialCorrupt)
    }

    @Test
    fun `recreated insights view model establishes a fresh repository subscription`() {
        // Mutation caught: a recreated page reading a retained legacy snapshot instead of subscribing again.
        val account = AccountInfo(
            id = "477714e0-7946-4c27-af96-051e42737429",
            label = "Stable",
            apiKey = "sk-stable-insights"
        )
        val source = RecordingAccountUiRepository(AccountLoadState.Ready(listOf(account)))
        val first = InsightsViewModel(app, source)
        awaitAccountState(first) { it.accounts == listOf(account) }
        val recreated = InsightsViewModel(app, source)
        awaitAccountState(recreated) { it.accounts == listOf(account) }

        assertEquals(2, source.subscriptionCount)
    }

    @Test
    fun `loadData populates daily output with summary data`() {
        val now = System.currentTimeMillis()
        val yesterday = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now - 24 * 3600_000L))

        addInsightsRoomSummaries(
            DailySummary(
                accountId = INSIGHTS_ACCOUNT_ID,
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

        val viewModel = createViewModel()
        awaitViewModel(viewModel)

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
        addInsightsRoomRecords(
            RawRecord(INSIGHTS_ACCOUNT_ID, now, "CNY", 100f, 0f, 100f),
            RawRecord(INSIGHTS_ACCOUNT_ID, now + 1000L, "USD", 50f, 0f, 50f)
        )

        val viewModel = createViewModel()
        awaitViewModel(viewModel)

        // Initial load picks first currency (CNY)
        assertEquals("CNY", viewModel.uiState.value.selectedCurrency)
        assertEquals(1, viewModel.uiState.value.intradayOutput!!.dataPointCount)
        assertEquals(100f, viewModel.uiState.value.intradayOutput!!.trendPoints[0].actualBalance)

        // Switch to USD
        viewModel.selectCurrency("USD")
        awaitViewModel(viewModel)

        assertEquals("USD", viewModel.uiState.value.selectedCurrency)
        assertEquals(1, viewModel.uiState.value.intradayOutput!!.dataPointCount)
        assertEquals(50f, viewModel.uiState.value.intradayOutput!!.trendPoints[0].actualBalance)
    }

    @Test
    fun `selected filters restore from saved state`() {
        val state = SavedStateHandle(
            mapOf(
                "insights.selectedAccountId" to "saved-account",
                "insights.selectedCurrency" to "USD",
                "insights.rangeDays" to 30
            )
        )

        val viewModel = InsightsViewModel(
            app,
            AccountUiRepository { flowOf(AccountLoadState.Ready(emptyList())) },
            savedStateHandle = state
        )

        assertEquals("saved-account", viewModel.uiState.value.selectedAccountId)
        assertEquals("USD", viewModel.uiState.value.selectedCurrency)
        assertEquals(30, viewModel.uiState.value.rangeDays)
    }
    @Test
    fun `setRangeDays triggers reload`() {
        val viewModel = createViewModel()

        assertEquals(7, viewModel.uiState.value.rangeDays)

        viewModel.setRangeDays(30)
        awaitViewModel(viewModel)
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

    // ── Chart mode switching ──

    @Test
    fun `setChartMode updates state without reloading data`() {
        val viewModel = createViewModel()

        assertEquals("balance", viewModel.uiState.value.chartMode)

        viewModel.setChartMode("consumed")
        assertEquals("consumed", viewModel.uiState.value.chartMode)

        viewModel.setChartMode("balance")
        assertEquals("balance", viewModel.uiState.value.chartMode)
    }

    @Test
    fun `chartMode preserved but history reset on currency switch`() {
        val now = System.currentTimeMillis()
        addInsightsRoomRecords(
            RawRecord(INSIGHTS_ACCOUNT_ID, now, "CNY", 100f, 0f, 100f),
            RawRecord(INSIGHTS_ACCOUNT_ID, now + 1000L, "USD", 50f, 0f, 50f)
        )

        val viewModel = createViewModel()
        awaitViewModel(viewModel)

        // Set consumption mode and expand a row
        viewModel.setChartMode("consumed")
        viewModel.toggleExpandDate("2026-07-01")

        assertEquals("consumed", viewModel.uiState.value.chartMode)
        assertEquals("2026-07-01", viewModel.uiState.value.expandedDate)

        // Switch currency → data reload, history reset, chartMode preserved
        viewModel.selectCurrency("USD")
        awaitViewModel(viewModel)

        assertEquals("consumed", viewModel.uiState.value.chartMode)  // preserved
        assertNull(viewModel.uiState.value.expandedDate)              // reset
        assertEquals(7, viewModel.uiState.value.historyVisibleCount)  // reset
    }

    // ── History pagination ──

    @Test
    fun `historyVisibleCount starts at 7`() {
        val viewModel = createViewModel()
        assertEquals(7, viewModel.uiState.value.historyVisibleCount)
    }

    @Test
    fun `loadMoreHistory increases visible count by 10 capped at data size`() {
        val now = System.currentTimeMillis()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for (i in 1..30) {
            val date = dateFormat.format(java.util.Date(now - (30 - i + 1) * 24 * 3600_000L))
            addInsightsRoomSummaries(
                DailySummary(
                    accountId = INSIGHTS_ACCOUNT_ID, date = date, currency = "CNY",
                    open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                    granted = 0f, avgBalance = 95f, sampleCount = 5,
                    toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
                )
            )
        }

        val viewModel = createViewModel()
        // Switch to 30-day range to include all 30 data points
        viewModel.setRangeDays(30)
        awaitViewModel(viewModel)

        assertEquals(7, viewModel.uiState.value.historyVisibleCount)

        viewModel.loadMoreHistory()
        assertEquals(17, viewModel.uiState.value.historyVisibleCount)

        viewModel.loadMoreHistory()
        assertEquals(27, viewModel.uiState.value.historyVisibleCount)

        // Capped at dailyPoints.size (30)
        viewModel.loadMoreHistory()
        assertEquals(30, viewModel.uiState.value.historyVisibleCount)
    }

    // ── Expand/collapse ──

    @Test
    fun `toggleExpandDate toggles expanded date`() {
        val viewModel = createViewModel()
        awaitAccountState(viewModel) { state ->
            !state.isLoading && state.dailyOutput != null
        }

        assertNull(viewModel.uiState.value.expandedDate)

        viewModel.toggleExpandDate("2026-07-01")
        assertEquals("2026-07-01", viewModel.uiState.value.expandedDate)

        viewModel.toggleExpandDate("2026-07-01")
        assertNull(viewModel.uiState.value.expandedDate)
    }

    // ═══════════════════════════════════════════════════════════
    // mergeIntradayOutputs — 多账户合并 (carry-forward)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mergeIntradayOutputs empty list returns empty output`() {
        val result = InsightsViewModel.mergeIntradayOutputs(emptyList())
        assertEquals(0, result.dataPointCount)
        assertTrue(result.trendPoints.isEmpty())
        assertEquals(0f, result.billReport.consumed)
        assertEquals(0f, result.billReport.toppedUp)
        assertEquals(0f, result.billReport.granted)
        assertEquals(0f, result.billReport.netChange)
    }

    @Test
    fun `mergeIntradayOutputs single output returns as-is`() {
        val output = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1000L, 100f, false, false, 0f, 0f)
            ),
            billReport = IntradayBillReport(10f, 5f, 0f, -5f),
            dataPointCount = 1
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(output))
        assertSame(output, result)
    }

    @Test
    fun `mergeIntradayOutputs two accounts same timestamps sums balances`() {
        val a = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1000L, 100f, false, false, 0f, 0f),
                IntradayPoint(2000L, 90f, false, false, 0f, 0f)
            ),
            billReport = IntradayBillReport(10f, 0f, 0f, -10f),
            dataPointCount = 2
        )
        val b = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1000L, 200f, false, false, 0f, 0f),
                IntradayPoint(2000L, 180f, false, false, 0f, 0f)
            ),
            billReport = IntradayBillReport(20f, 0f, 0f, -20f),
            dataPointCount = 2
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(a, b))
        assertEquals(2, result.trendPoints.size)
        assertEquals(300f, result.trendPoints[0].actualBalance)
        assertEquals(270f, result.trendPoints[1].actualBalance)
        assertEquals(30f, result.billReport.consumed)
        assertEquals(2, result.dataPointCount)
    }

    @Test
    fun `mergeIntradayOutputs carry-forward fills missing timestamps`() {
        // Account A has points at 1000 and 2000 (inserted first → LinkedHashMap order)
        val a = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1000L, 100f, false, false, 0f, 0f),
                IntradayPoint(2000L, 80f, false, false, 0f, 0f)
            ),
            billReport = IntradayBillReport(20f, 0f, 0f, -20f),
            dataPointCount = 2
        )
        // Account B has a point at 1500 only (inserted second → appears after A's points)
        val b = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1500L, 50f, false, false, 0f, 0f)
            ),
            billReport = IntradayBillReport(0f, 0f, 0f, 0f),
            dataPointCount = 1
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(a, b))
        // LinkedHashMap maintains insertion order from iterating outputs:
        // t=1000 (from A), t=2000 (from A), t=1500 (from B)
        // At t=1000: A=100 → totalBalance=100
        // At t=2000: A=80 → totalBalance=80
        // At t=1500: B=50, A still 80 (carry-forward) → totalBalance=130
        assertEquals(3, result.trendPoints.size)
        assertEquals(1000L, result.trendPoints[0].timestamp)
        assertEquals(100f, result.trendPoints[0].actualBalance)
        assertEquals(2000L, result.trendPoints[1].timestamp)
        assertEquals(80f, result.trendPoints[1].actualBalance)
        assertEquals(1500L, result.trendPoints[2].timestamp)
        assertEquals(130f, result.trendPoints[2].actualBalance)
    }

    @Test
    fun `mergeIntradayOutputs sums topUp and grant amounts`() {
        val a = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1000L, 100f, true, false, 50f, 0f)
            ),
            billReport = IntradayBillReport(0f, 50f, 0f, 50f),
            dataPointCount = 1
        )
        val b = IntradayOutput(
            trendPoints = listOf(
                IntradayPoint(1000L, 200f, false, true, 0f, 30f)
            ),
            billReport = IntradayBillReport(0f, 0f, 30f, 30f),
            dataPointCount = 1
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(a, b))
        assertEquals(1, result.trendPoints.size)
        assertTrue(result.trendPoints[0].isTopUp)
        assertTrue(result.trendPoints[0].isGrant)
        assertEquals(50f, result.trendPoints[0].topUpAmount)
        assertEquals(30f, result.trendPoints[0].grantAmount)
        assertEquals(50f, result.billReport.toppedUp)
        assertEquals(30f, result.billReport.granted)
        assertEquals(80f, result.billReport.netChange)
    }

    @Test
    fun `mergeIntradayOutputs bill report netChange computed correctly`() {
        val a = IntradayOutput(
            trendPoints = listOf(IntradayPoint(1000L, 100f, false, false, 0f, 0f)),
            billReport = IntradayBillReport(consumed = 10f, toppedUp = 30f, granted = 5f, netChange = 25f),
            dataPointCount = 1
        )
        val b = IntradayOutput(
            trendPoints = listOf(IntradayPoint(1000L, 200f, false, false, 0f, 0f)),
            billReport = IntradayBillReport(consumed = 5f, toppedUp = 10f, granted = 0f, netChange = 5f),
            dataPointCount = 1
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(a, b))
        assertEquals(15f, result.billReport.consumed)
        assertEquals(40f, result.billReport.toppedUp)
        assertEquals(5f, result.billReport.granted)
        assertEquals(30f, result.billReport.netChange)  // 40 + 5 - 15
    }

    @Test
    fun `mergeIntradayOutputs adaptive downsampling less than 20 points no sampling`() {
        val outputs = listOf(
            IntradayOutput(
                trendPoints = (1..15).map { i ->
                    IntradayPoint(i * 1000L, (100 - i).toFloat(), false, false, 0f, 0f)
                },
                billReport = IntradayBillReport(0f, 0f, 0f, 0f),
                dataPointCount = 15
            ),
            IntradayOutput(
                trendPoints = listOf(IntradayPoint(16000L, 50f, false, false, 0f, 0f)),
                billReport = IntradayBillReport(0f, 0f, 0f, 0f),
                dataPointCount = 1
            )
        )
        val result = InsightsViewModel.mergeIntradayOutputs(outputs)
        // 16 unique timestamps, all within 20 → no downsampling
        assertEquals(16, result.trendPoints.size)
        assertEquals(16, result.dataPointCount)
    }

    @Test
    fun `mergeIntradayOutputs adaptive downsampling with many points`() {
        // Create 2 accounts each with 40 interleaved points → 80 total → triggers 30s sampling
        val a = IntradayOutput(
            trendPoints = (0 until 40).map { i ->
                IntradayPoint((i * 1000).toLong(), (100f - i), false, false, 0f, 0f)
            },
            billReport = IntradayBillReport(0f, 0f, 0f, 0f),
            dataPointCount = 40
        )
        val b = IntradayOutput(
            trendPoints = (0 until 40).map { i ->
                IntradayPoint((i * 1000 + 500).toLong(), (200f - i * 2), false, false, 0f, 0f)
            },
            billReport = IntradayBillReport(0f, 0f, 0f, 0f),
            dataPointCount = 40
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(a, b))
        // 80 unique timestamps → minInterval=30000ms → downsampled
        assertTrue(result.trendPoints.size < 80)
        assertTrue(result.trendPoints.isNotEmpty())
        // Tail should be preserved
        val lastTs = result.trendPoints.last().timestamp
        assertEquals(39500L, lastTs)
    }

    @Test
    fun `mergeIntradayOutputs preserves tail point after downsampling`() {
        // 2 accounts × 25 interleaved points = 50 → minInterval=15000
        val a = IntradayOutput(
            trendPoints = (0 until 25).map { i ->
                IntradayPoint((i * 2000).toLong(), (100f - i * 2), false, false, 0f, 0f)
            },
            billReport = IntradayBillReport(0f, 0f, 0f, 0f),
            dataPointCount = 25
        )
        val b = IntradayOutput(
            trendPoints = (0 until 25).map { i ->
                IntradayPoint((i * 2000 + 1000).toLong(), (200f - i * 3), false, false, 0f, 0f)
            },
            billReport = IntradayBillReport(0f, 0f, 0f, 0f),
            dataPointCount = 25
        )
        val result = InsightsViewModel.mergeIntradayOutputs(listOf(a, b))
        val expectedTail = 49000L  // last b point: 24*2000+1000 = 49000
        assertEquals(expectedTail, result.trendPoints.last().timestamp)
    }

    // ═══════════════════════════════════════════════════════════
    // mergeDailyOutputs — 多账户合并
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mergeDailyOutputs empty list returns empty output`() {
        val result = InsightsViewModel.mergeDailyOutputs(emptyList(), 7)
        assertTrue(result.isEmpty)
        assertTrue(result.insufficientData)
        assertTrue(result.dailyPoints.isEmpty())
        assertEquals(0f, result.billReport.consumed)
        assertNull(result.estimate)
    }

    @Test
    fun `mergeDailyOutputs single output returns as-is`() {
        val output = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false, 100f, 5)
            ),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "7天"),
            estimate = DepletionEstimate(1.5f, 67f, 9, 15, EstimateMethod.AVERAGE, 7),
            periodLabel = "7天",
            isEmpty = false,
            insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(output), 7)
        assertSame(output, result)
    }

    @Test
    fun `mergeDailyOutputs same dates summed across accounts`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 5f, 0f, false, 100f, 3),
                DailyPoint("2026-07-02", 90f, 10f, 0f, 0f, false, 90f, 2)
            ),
            billReport = DailyBillReport(20f, 5f, 0f, -15f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 200f, 15f, 0f, 5f, false, 200f, 5),
                DailyPoint("2026-07-02", 180f, 10f, 5f, 0f, false, 180f, 4)
            ),
            billReport = DailyBillReport(25f, 5f, 5f, -15f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertEquals(2, result.dailyPoints.size)
        assertEquals("2026-07-01", result.dailyPoints[0].date)
        assertEquals(300f, result.dailyPoints[0].balance)   // 100+200
        assertEquals(25f, result.dailyPoints[0].consumed)    // 10+15
        assertEquals(5f, result.dailyPoints[0].toppedUp)     // 5+0
        assertEquals(5f, result.dailyPoints[0].granted)      // 0+5
        assertEquals(300f, result.dailyPoints[0].open)       // 100+200
        assertEquals(5, result.dailyPoints[0].sampleCount)   // max(3,5)
    }

    @Test
    fun `mergeDailyOutputs different dates merged chronologically`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false)
            ),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-03", 200f, 5f, 0f, 0f, false)
            ),
            billReport = DailyBillReport(5f, 0f, 0f, -5f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertEquals(2, result.dailyPoints.size)
        assertEquals("2026-07-01", result.dailyPoints[0].date)
        assertEquals("2026-07-03", result.dailyPoints[1].date)
    }

    @Test
    fun `mergeDailyOutputs isGapFill true only when both have gap fill`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, isGapFill = true)
            ),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 200f, 5f, 0f, 0f, isGapFill = false)
            ),
            billReport = DailyBillReport(5f, 0f, 0f, -5f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertFalse(result.dailyPoints[0].isGapFill)
    }

    @Test
    fun `mergeDailyOutputs isGapFill true when both true`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, isGapFill = true)
            ),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 200f, 5f, 0f, 0f, isGapFill = true)
            ),
            billReport = DailyBillReport(5f, 0f, 0f, -5f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertTrue(result.dailyPoints[0].isGapFill)
    }

    @Test
    fun `mergeDailyOutputs sums bill report across accounts`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 5f, 2f, false)
            ),
            billReport = DailyBillReport(consumed = 10f, toppedUp = 5f, granted = 2f, netChange = -3f, periodLabel = "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 200f, 8f, 3f, 1f, false)
            ),
            billReport = DailyBillReport(consumed = 8f, toppedUp = 3f, granted = 1f, netChange = -4f, periodLabel = "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertEquals(18f, result.billReport.consumed)
        assertEquals(8f, result.billReport.toppedUp)
        assertEquals(3f, result.billReport.granted)
        assertEquals(-7f, result.billReport.netChange)  // 8+3-18
    }

    @Test
    fun `mergeDailyOutputs periodLabel from first output`() {
        val a = DailyOutput(
            dailyPoints = listOf(DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false)),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "30天"),
            estimate = null, periodLabel = "30天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(DailyPoint("2026-07-01", 200f, 5f, 0f, 0f, false)),
            billReport = DailyBillReport(5f, 0f, 0f, -5f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 30)
        assertEquals("30天", result.periodLabel)
    }

    // ═══════════════════════════════════════════════════════════
    // mergeDailyOutputs → computeMergedEstimate (via mergeDailyOutputs)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mergeDailyOutputs estimate null when no consumption data`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 0f, 0f, 0f, false)  // consumed=0
            ),
            billReport = DailyBillReport(0f, 0f, 0f, 0f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 200f, 0f, 0f, 0f, false)
            ),
            billReport = DailyBillReport(0f, 0f, 0f, 0f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertNull(result.estimate)
        assertTrue(result.insufficientData)
    }

    @Test
    fun `mergeDailyOutputs estimate simple count when less than 3 days`() {
        // 2 accounts, each with 2 days of data with consumption → merged gives 2 days
        val ptsA = listOf(
            DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false),
            DailyPoint("2026-07-02", 90f, 10f, 0f, 0f, false)
        )
        val ptsB = listOf(
            DailyPoint("2026-07-01", 200f, 15f, 0f, 0f, false),
            DailyPoint("2026-07-02", 185f, 15f, 0f, 0f, false)
        )
        val a = DailyOutput(ptsA, DailyBillReport(20f, 0f, 0f, -20f, "7天"), null, "7天", false, false)
        val b = DailyOutput(ptsB, DailyBillReport(30f, 0f, 0f, -30f, "7天"), null, "7天", false, false)
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertNotNull(result.estimate)
        assertEquals(EstimateMethod.MULTI_ACCOUNT_SIMPLE_COUNT, result.estimate!!.method)
        assertEquals(2, result.estimate!!.methodDays)
        assertTrue(result.estimate!!.dailyRate > 0f)
    }

    @Test
    fun `mergeDailyOutputs estimate linear regression with 3 plus days`() {
        // 3 days with increasing consumption → positive slope → LINEAR_REGRESSION
        val ptsA = listOf(
            DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false),
            DailyPoint("2026-07-02", 90f, 12f, 0f, 0f, false),
            DailyPoint("2026-07-03", 78f, 14f, 0f, 0f, false)   // increasing consumed
        )
        val ptsB = listOf(
            DailyPoint("2026-07-01", 200f, 5f, 0f, 0f, false),
            DailyPoint("2026-07-02", 195f, 7f, 0f, 0f, false),
            DailyPoint("2026-07-03", 188f, 9f, 0f, 0f, false)
        )
        val a = DailyOutput(ptsA, DailyBillReport(36f, 0f, 0f, -36f, "7天"), null, "7天", false, false)
        val b = DailyOutput(ptsB, DailyBillReport(21f, 0f, 0f, -21f, "7天"), null, "7天", false, false)
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertNotNull(result.estimate)
        assertEquals(EstimateMethod.MULTI_ACCOUNT_LINEAR_REGRESSION, result.estimate!!.method)
        assertEquals(7, result.estimate!!.methodDays)
        assertTrue(result.estimate!!.dailyRate > 0f)
        assertTrue(result.estimate!!.daysRemaining > 0f)
    }

    @Test
    fun `mergeDailyOutputs estimate falls back to average when slope negative`() {
        // 3 days with decreasing consumption → negative slope → falls back to AVERAGE
        // Need 2+ outputs to enter the merge path (single output returns as-is)
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 20f, 0f, 0f, false),
                DailyPoint("2026-07-02", 80f, 15f, 0f, 0f, false),
                DailyPoint("2026-07-03", 65f, 10f, 0f, 0f, false)  // decreasing
            ),
            billReport = DailyBillReport(45f, 0f, 0f, -45f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        // Second identical output to trigger merge path
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 50f, 5f, 0f, 0f, false),
                DailyPoint("2026-07-02", 45f, 3f, 0f, 0f, false),
                DailyPoint("2026-07-03", 42f, 2f, 0f, 0f, false)
            ),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertNotNull(result.estimate)
        assertEquals(EstimateMethod.MULTI_ACCOUNT_AVERAGE, result.estimate!!.method)
    }

    @Test
    fun `mergeDailyOutputs insufficientData false when has consumption`() {
        val a = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false)
            ),
            billReport = DailyBillReport(10f, 0f, 0f, -10f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val b = DailyOutput(
            dailyPoints = listOf(
                DailyPoint("2026-07-01", 200f, 5f, 0f, 0f, false)
            ),
            billReport = DailyBillReport(5f, 0f, 0f, -5f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a, b), 7)
        assertFalse(result.insufficientData)
    }

    @Test
    fun `mergeDailyOutputs isEmpty true when no points at all`() {
        val result = InsightsViewModel.mergeDailyOutputs(emptyList(), 7)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `mergeDailyOutputs isEmpty false when has merged data`() {
        val a = DailyOutput(
            dailyPoints = listOf(DailyPoint("2026-07-01", 100f, 5f, 0f, 0f, false)),
            billReport = DailyBillReport(5f, 0f, 0f, -5f, "7天"),
            estimate = null, periodLabel = "7天", isEmpty = false, insufficientData = false
        )
        val result = InsightsViewModel.mergeDailyOutputs(listOf(a), 7)
        assertFalse(result.isEmpty)
    }

    // ═══════════════════════════════════════════════════════════
    // selectAccount — 账户切换
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `selectAccount null goes to all-accounts mode`() {
        val viewModel = createViewModel()
        viewModel.selectAccount(null)
        assertNull(viewModel.uiState.value.selectedAccountId)
    }

    @Test
    fun `selectAccount sets specific account`() {
        val viewModel = createViewModel()
        viewModel.selectAccount("specific-id")
        assertEquals("specific-id", viewModel.uiState.value.selectedAccountId)
    }

    // ═══════════════════════════════════════════════════════════
    // InsightsUiState.isEmpty
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `InsightsUiState isEmpty true when both outputs empty`() {
        val state = InsightsUiState(
            intradayOutput = IntradayOutput(emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0),
            dailyOutput = DailyOutput(emptyList(), DailyBillReport(0f, 0f, 0f, 0f, ""), null, "", true, true)
        )
        assertTrue(state.isEmpty)
    }

    @Test
    fun `InsightsUiState isEmpty false when intraday has data`() {
        val state = InsightsUiState(
            intradayOutput = IntradayOutput(
                listOf(IntradayPoint(1000L, 100f, false, false, 0f, 0f)),
                IntradayBillReport(0f, 0f, 0f, 0f), 1
            ),
            dailyOutput = DailyOutput(emptyList(), DailyBillReport(0f, 0f, 0f, 0f, ""), null, "", true, true)
        )
        assertFalse(state.isEmpty)
    }

    @Test
    fun `InsightsUiState isEmpty false when daily has data`() {
        val state = InsightsUiState(
            intradayOutput = IntradayOutput(emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0),
            dailyOutput = DailyOutput(
                listOf(DailyPoint("2026-07-01", 100f, 10f, 0f, 0f, false)),
                DailyBillReport(10f, 0f, 0f, -10f, ""), null, "", false, false
            )
        )
        assertFalse(state.isEmpty)
    }

    @Test
    fun `InsightsUiState isEmpty handles null outputs`() {
        val state = InsightsUiState(intradayOutput = null, dailyOutput = null)
        assertTrue(state.isEmpty)
    }

    private fun awaitAccountState(
        viewModel: InsightsViewModel,
        predicate: (InsightsUiState) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 5000
        while (!predicate(viewModel.uiState.value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for account state", predicate(viewModel.uiState.value))
    }

    private fun createViewModel(): InsightsViewModel = InsightsViewModel(
        app,
        AccountUiRepository { flowOf(AccountLoadState.Ready(listOf(insightsAccountInfo()))) }
    )

    private fun addInsightsRoomRecords(vararg records: RawRecord) = runBlocking {
        RoomHistoryRepository(database).insert(records.toList(), BalanceRecordSource.REFRESH)
    }

    private fun addInsightsRoomSummaries(vararg summaries: DailySummary) = runBlocking {
        RoomHistoryRepository(database).upsertSummaries(summaries.toList())
    }

    private fun insightsAccountInfo() = AccountInfo(
        id = INSIGHTS_ACCOUNT_ID,
        label = "Insights test account",
        apiKey = "sk-insights-test",
        providerType = ProviderType.DEEPSEEK,
        revision = 1
    )

    private fun insightsRoomAccount() = AccountEntity(
        id = INSIGHTS_ACCOUNT_ID,
        displayOrder = 0,
        label = "Insights test account",
        providerType = ProviderType.DEEPSEEK,
        activeCredentialGeneration = "test",
        state = AccountState.VERIFIED,
        revision = 1,
        createdAt = 1L,
        updatedAt = 1L
    )

    private class RecordingAccountUiRepository(
        initial: AccountLoadState
    ) : AccountUiRepository {
        private val states = MutableStateFlow(initial)
        var subscriptionCount: Int = 0
            private set

        override fun observe(): Flow<AccountLoadState> = flow {
            subscriptionCount++
            emitAll(states)
        }

        fun publish(state: AccountLoadState) {
            states.value = state
        }
    }

    private companion object {
        const val INSIGHTS_ACCOUNT_ID = "7f4b8b3e-3f71-4d4f-a1c3-6c7e5a9b2d10"
    }
}
