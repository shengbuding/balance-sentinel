package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountLifecycleManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
    @After fun close() = db.close()

    @Test fun `account deletion uses foreign key cascade for all owned Room rows`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1L, totalBalance = 1.0, source = BalanceRecordSource.REFRESH)))
        db.usageDao().upsertSnapshot(UsageSnapshotEntity("usage", "acct", 1L, "refresh"))
        db.eventLogDao().insertAll(listOf(EventLogEntity(id = 1L, accountId = "acct", eventType = EventLogType.AUTO, recordedAt = 1L)))
        db.accountDao().deleteWhereRevision("acct", 0L)
        assertEquals(0, db.historyDao().countRecords())
        assertEquals(0, db.usageDao().countSnapshots())
        assertEquals(0, db.eventLogDao().countLogs())
    }
}
