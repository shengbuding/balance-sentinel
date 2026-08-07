package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.engine.RecordAggregator
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {
    private lateinit var database: WalletDatabase
    private lateinit var repository: HistoryRepository
    private val accountId = "history-repository-account"

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
        runBlocking { database.accountDao().insertCreate(testAccount(accountId)) }
        runBlocking { database.accountDao().insertCreate(testAccount("other-account", displayOrder = 1)) }
        repository = RoomHistoryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
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
        assertEquals(expected.sampleCount, actual.sampleCount)
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
}
