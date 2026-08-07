package com.balancesentinel.app.data.local.history

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
