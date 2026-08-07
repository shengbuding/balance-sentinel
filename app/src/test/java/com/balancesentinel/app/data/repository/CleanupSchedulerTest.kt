package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CleanupSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
    @After fun close() = db.close()

    @Test fun `cleanup writes summary before deleting exact archived raw ids`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1_000L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH),
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 2_000L, totalBalance = 9.0, source = BalanceRecordSource.REFRESH)
        ))
        val report = CleanupScheduler.runCleanup(RoomTestContext(context, db), 3 * 86_400_000L, ZoneOffset.UTC, RoomHistoryRepository(db))
        assertTrue(report.archivedDates.contains("1970-01-01"))
        assertEquals(0, db.historyDao().countRecords())
        assertEquals(1, db.historyDao().countSummaries())
    }

    private class RoomTestContext(base: Context, val database: WalletDatabase) : android.content.ContextWrapper(base)
}
