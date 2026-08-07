package com.balancesentinel.app.data.local.history

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryDaoTest {
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `keyset pages use recorded timestamp and id without crossing account or currency`() = runTest {
        database.accountDao().insertCreate(testAccount("history-a"))
        database.accountDao().insertCreate(testAccount("history-b"))
        database.historyDao().insertBalanceBatch(
            listOf(
                BalanceRecordEntity(accountId = "history-a", currency = "USD", recordedAt = 100, totalBalance = 1.0),
                BalanceRecordEntity(accountId = "history-a", currency = "USD", recordedAt = 100, totalBalance = 2.0),
                BalanceRecordEntity(accountId = "history-a", currency = "USD", recordedAt = 90, totalBalance = 3.0),
                BalanceRecordEntity(accountId = "history-a", currency = "EUR", recordedAt = 100, totalBalance = 4.0),
                BalanceRecordEntity(accountId = "history-b", currency = "USD", recordedAt = 100, totalBalance = 5.0)
            )
        )

        val first = database.historyDao().keysetPage("history-a", "USD", 0, 101, null, null, 2)
        val last = first.last()
        val second = database.historyDao().keysetPage(
            "history-a", "USD", 0, 101, last.recordedAt, last.id, 2
        )

        assertEquals(listOf(100L, 100L, 90L), (first + second).map { it.recordedAt })
        assertEquals(3, (first + second).map { it.id }.distinct().size)
    }

    @Test
    fun `global keyset page uses recorded at id index without temporary sort`() = runTest {
        database.accountDao().insertCreate(testAccount("history-plan"))
        database.historyDao().insertBalanceBatch(
            (0 until 1_000).map { offset ->
                BalanceRecordEntity(
                    accountId = "history-plan",
                    currency = "USD",
                    recordedAt = offset.toLong(),
                    totalBalance = offset.toDouble()
                )
            }
        )

        val page = database.historyDao().keysetPageAll(
            fromInclusive = 0,
            toExclusive = 1_000,
            afterRecordedAt = null,
            afterId = null,
            limit = 200
        )
        assertEquals(200, page.size)

        val plan = withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT * FROM balance_records " +
                    "WHERE recorded_at >= ? AND recorded_at < ? " +
                    "AND (? IS NULL OR recorded_at < ? OR " +
                    "(recorded_at = ? AND id < ?)) " +
                    "ORDER BY recorded_at DESC, id DESC LIMIT ?",
                arrayOf("0", "1000", null, null, null, null, "200")
            ).use { cursor ->
                buildList {
                    val detailIndex = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(detailIndex))
                }
            }
        }

        assertTrue("Expected the global keyset index in query plan: $plan", plan.any {
            it.contains("index_balance_records_recorded_at_id")
        })
        assertTrue("Global keyset query must not use a temporary ORDER BY sort: $plan", plan.none {
            it.contains("USE TEMP B-TREE FOR ORDER BY")
        })
    }
}
