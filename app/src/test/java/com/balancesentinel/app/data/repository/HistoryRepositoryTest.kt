package com.balancesentinel.app.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.ui.viewmodel.InsightsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {
    private lateinit var database: WalletDatabase
    private lateinit var repository: HistoryRepository
    private val accountId = "history-repository-account"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = createWalletTestDatabase()
        runBlocking { database.accountDao().insertCreate(testAccount(accountId)) }
        runBlocking { database.accountDao().insertCreate(testAccount("other-account", displayOrder = 1)) }
        repository = RoomHistoryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `ninety thousand records page at most two hundred with no gaps or duplicates`() = runBlocking {
        val source = (0 until 90_000).map { index ->
            RawRecord(accountId, index.toLong(), "USD", index.toFloat(), 0f, 0f)
        }
        assertEquals(90_000, repository.insert(source, BalanceRecordSource.REFRESH))

        val seen = mutableListOf<Long>()
        var cursor: HistoryCursor? = null
        do {
            val page = repository.page(accountId, "USD", 0, 90_000, cursor, 10_000)
            assertTrue(page.records.size <= 200)
            seen += page.records.map { it.value.timestamp }
            cursor = page.nextCursor
        } while (page.records.isNotEmpty())

        assertEquals(90_000, seen.size)
        assertEquals(90_000, seen.toSet().size)
        assertEquals((89_999L downTo 0L).toList(), seen)
    }

    @Test
    fun `range account currency and unknown ISO currency are enforced`() = runTest {
        repository.insert(
            listOf(
                RawRecord(accountId, 10, "USD", 10f, 0f, 0f),
                RawRecord(accountId, 20, "EUR", 20f, 0f, 0f),
                RawRecord("other-account", 20, "USD", 30f, 0f, 0f)
            ),
            BalanceRecordSource.REFRESH
        )
        assertEquals(1L, repository.count(accountId, "USD", 0, 20))
        assertEquals(listOf(10L), repository.page(accountId, "USD", 0, 20).records.map { it.value.timestamp })
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.page(accountId, "NOT_A_CURRENCY", 0, 100) }
        }
    }

    @Test
    fun `database aggregate preserves RecordAggregator accounting semantics`() = runTest {
        val records = listOf(
            RawRecord(accountId, 1, "USD", 100f, 0f, 10f),
            RawRecord(accountId, 2, "USD", 80f, 0f, 10f),
            RawRecord(accountId, 3, "USD", 95f, 5f, 10f),
            RawRecord(accountId, 4, "USD", 90f, 5f, 15f)
        )
        repository.insert(records, BalanceRecordSource.REFRESH)
        val expected = RecordAggregator.aggregate(records, "2026-08-07").single()
        val actual = requireNotNull(repository.aggregate(accountId, "USD", 0, 10))
        assertEquals(expected.open, actual.open)
        assertEquals(expected.close, actual.close)
        assertEquals(expected.consumed, actual.consumed, 0.001f)
        assertEquals(expected.toppedUp, actual.toppedUp, 0.001f)
        assertEquals(expected.granted, actual.granted, 0.001f)
        assertEquals(expected.avgBalance, actual.avgBalance, 0.001f)
        assertEquals(expected.sampleCount, actual.sampleCount)
        assertEquals(expected.toppedUpBalanceClose, actual.toppedUpBalanceClose, 0.001f)
        assertEquals(expected.grantedBalanceClose, actual.grantedBalanceClose, 0.001f)
    }

    @Test
    fun `database aggregate infers recharge when provider metadata is absent`() = runTest {
        val records = listOf(
            RawRecord(accountId, 1, "USD", 7.73f, 0f, 0f),
            RawRecord(accountId, 2, "USD", 10.00f, 0f, 0f)
        )
        repository.insert(records, BalanceRecordSource.REFRESH)

        val actual = requireNotNull(repository.aggregate(accountId, "USD", 0, 10))

        assertEquals(2.27f, actual.toppedUp, 0.01f)
        assertEquals(0f, actual.consumed, 0.01f)
    }

    @Test
    fun `aggregate handles no recharge noninteger jumps and duplicate timestamps`() = runTest {
        val records = listOf(
            RawRecord(accountId, 100, "USD", 100f, 1f, 10f),
            RawRecord(accountId, 100, "USD", 99f, 2f, 11.5f),
            RawRecord(accountId, 100, "USD", 98f, 2f, 12.25f),
            RawRecord(accountId, 200, "USD", 95f, 5f, 12.25f)
        )
        repository.insert(records, BalanceRecordSource.REFRESH)

        val actual = requireNotNull(repository.aggregate(accountId, "USD", 0, 201))
        assertEquals(100f, actual.open, 0.001f)
        assertEquals(95f, actual.close, 0.001f)
        assertEquals(9f, actual.consumed, 0.001f)
        assertEquals(0f, actual.toppedUp, 0.001f)
        assertEquals(4f, actual.granted, 0.001f)
        assertEquals(98f, actual.avgBalance, 0.001f)
        assertEquals(4, actual.sampleCount)
        assertEquals(12.25f, actual.toppedUpBalanceClose, 0.001f)
        assertEquals(5f, actual.grantedBalanceClose, 0.001f)
    }

    @Test
    fun `summary primary key remains unique and range filters are inclusive`() = runTest {
        val first = DailySummary(accountId, "2026-08-01", "USD", 1f, 2f, 0f, 0f, avgBalance = 1.5f, sampleCount = 2)
        val replacement = first.copy(close = 9f)
        repository.upsertSummaries(listOf(first))
        repository.upsertSummaries(listOf(replacement))
        assertEquals(1, repository.summaries(accountId = accountId, currency = "USD").size)
        assertEquals(9f, repository.summaries(accountId = accountId, currency = "USD", fromDateInclusive = "2026-08-01", toDateInclusive = "2026-08-01").single().close)
    }

    @Test
    fun `Insights entry point uses the injected history repository page`() {
        val entryRepository = RecordingHistoryRepository(
            records = listOf(HistoryRecord(1, RawRecord(accountId, 100, "USD", 10f, 0f, 0f)))
        )
        val accountSource = AccountUiRepository {
            flowOf(
                AccountLoadState.Ready(
                    listOf(AccountInfo(accountId, "History", "key"))
                )
            )
        }
        val viewModel = InsightsViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            accountSource,
            entryRepository
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (entryRepository.pageCalls == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(entryRepository.pageCalls > 0)
    }

    @Test
    fun `Cleanup entry point reads history through the injected page repository`() = runTest {
        val entryRepository = RecordingHistoryRepository(
            records = listOf(HistoryRecord(1, RawRecord(accountId, 100, "USD", 10f, 0f, 0f)))
        )
        CleanupScheduler.runCleanup(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            200_000,
            java.time.ZoneOffset.UTC,
            entryRepository
        )
        assertTrue(entryRepository.pageAllCalls > 0)
    }

    @Test
    fun `DataExporter entry point serializes records obtained from repository pages`() = runTest {
        val entryRepository = RecordingHistoryRepository(
            records = listOf(HistoryRecord(1, RawRecord(accountId, 100, "USD", 10f, 0f, 0f)))
        )
        val exported = DataExporter.buildExport(
            ApplicationProvider.getApplicationContext(),
            entryRepository
        )
        val parsed = kotlinx.serialization.json.Json.decodeFromString<DataExport>(exported)
        assertEquals(listOf(100L), parsed.rawRecords.map { it.timestamp })
        assertTrue(entryRepository.pageAllCalls > 0)
    }

    private class RecordingHistoryRepository(
        private val records: List<HistoryRecord>
    ) : HistoryRepository {
        @Volatile var pageCalls: Int = 0
            private set
        @Volatile var pageAllCalls: Int = 0
            private set

        override suspend fun insert(
            records: List<RawRecord>,
            source: com.balancesentinel.app.data.local.history.BalanceRecordSource
        ): Int = error("unused")

        override suspend fun page(
            accountId: String,
            currency: String,
            fromInclusive: Long,
            toExclusive: Long,
            after: HistoryCursor?,
            limit: Int
        ): HistoryPage {
            pageCalls++
            return HistoryPage(records, null)
        }

        override suspend fun pageAll(
            fromInclusive: Long,
            toExclusive: Long,
            after: HistoryCursor?,
            limit: Int
        ): HistoryPage {
            pageAllCalls++
            return HistoryPage(records, null)
        }

        override suspend fun aggregate(
            accountId: String,
            currency: String,
            fromInclusive: Long,
            toExclusive: Long
        ): HistoryAggregate? = null

        override suspend fun count(
            accountId: String,
            currency: String,
            fromInclusive: Long,
            toExclusive: Long
        ): Long = records.size.toLong()

        override suspend fun distinctCurrencies(): List<String> = records.map { it.value.currency }.distinct()

        override suspend fun summaries(
            accountId: String?,
            currency: String?,
            fromDateInclusive: String?,
            toDateInclusive: String?
        ): List<DailySummary> = emptyList()

        override suspend fun upsertSummaries(summaries: List<DailySummary>) = Unit
    }
}
