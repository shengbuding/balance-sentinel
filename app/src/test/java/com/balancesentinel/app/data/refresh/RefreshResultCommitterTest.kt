package com.balancesentinel.app.data.refresh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.repository.RoomRefreshPersistence
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshResultCommitterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()

    @Before fun installRoom() = com.balancesentinel.app.data.local.WalletDatabaseProvider.installForTests(db)
    @After fun close() = db.close()

    @Test fun `refresh records usage and logs commit atomically`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val persistence = RoomRefreshPersistence(db)
        val record = com.balancesentinel.app.data.model.RawRecord("acct", 10L, "USD", 3f, 0f, 0f)
        val usage = com.balancesentinel.app.data.model.UsageSnapshot("acct", 10L)
        val log = RefreshLogEntry(42L, RefreshLogType.AUTO, "3", "USD", timestamp = 10L)
        persistence.commit(listOf(record), listOf(usage), listOf(log), "refresh:10", "acct")
        assertEquals(1, db.historyDao().countRecords())
        assertEquals(1, db.usageDao().countSnapshots())
        assertEquals(1, db.eventLogDao().countLogs())
    }

    @Test fun `refresh transaction failure leaves all three tables unchanged`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val persistence = RoomRefreshPersistence(db)
        val record = com.balancesentinel.app.data.model.RawRecord("acct", 10L, "USD", 3f, 0f, 0f)
        val usage = com.balancesentinel.app.data.model.UsageSnapshot("acct", 10L)
        val duplicate = RefreshLogEntry(7L, RefreshLogType.AUTO, "3", "USD", timestamp = 10L)
        persistence.commit(emptyList(), emptyList(), listOf(duplicate), "seed", "acct")
        runCatching { persistence.commit(listOf(record), listOf(usage), listOf(duplicate), "refresh:10", "acct") }
        assertEquals(0, db.historyDao().countRecords())
        assertEquals(0, db.usageDao().countSnapshots())
        assertEquals(1, db.eventLogDao().countLogs())
    }

    @Test fun `default committer wiring rolls back Room rows when account FK rejects write`() {
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = AccountInfo(accountId, "Primary", "key", ProviderType.DEEPSEEK, revision = 0)
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { }
        )
        val result = committer.commit(RefreshRequest("acct", 0, 1, RefreshTrigger.SERVICE, 0), BalanceFetchResult.Success(UnifiedBalance(ProviderType.DEEPSEEK, "acct", true, listOf(BalanceEntry("USD", 1.0))), 10L)) { true }
        assertTrue(result is AccountRefreshResult.Failed)
        runBlocking { assertEquals(0, db.historyDao().countRecords()); assertEquals(0, db.usageDao().countSnapshots()); assertEquals(0, db.eventLogDao().countLogs()) }
    }

    @Test fun `production committer entry writes all Room tables`() {
        runBlocking { db.accountDao().insertCreate(accountEntity()) }
        val committer = RefreshResultCommitter(context, object : RefreshAccountStore {
            override fun getAccount(accountId: String) = AccountInfo(accountId, "Primary", "key", ProviderType.DEEPSEEK, revision = 0)
            override fun getAccounts() = emptyList<AccountInfo>()
        }, roomPersistence = RoomRefreshPersistence(db), alertDispatcher = RefreshAlertDispatcher { _, _ -> }, widgetRedrawNotifier = WidgetRedrawNotifier { })
        val result = committer.commit(RefreshRequest("acct", 0, 1, RefreshTrigger.SERVICE, 0), BalanceFetchResult.Success(UnifiedBalance(ProviderType.DEEPSEEK, "acct", true, listOf(BalanceEntry("USD", 1.0))), 10L)) { true }
        assertTrue(result is AccountRefreshResult.Committed)
        runBlocking { assertEquals(1, db.historyDao().countRecords()); assertEquals(1, db.usageDao().countSnapshots()); assertEquals(1, db.eventLogDao().countLogs()) }
    }

    @Test fun `default committer constructor uses installed Room database`() {
        runBlocking { db.accountDao().insertCreate(accountEntity()) }
        val committer = RefreshResultCommitter(context, object : RefreshAccountStore {
            override fun getAccount(accountId: String) = AccountInfo(accountId, "Primary", "key", ProviderType.DEEPSEEK, revision = 0)
            override fun getAccounts() = emptyList<AccountInfo>()
        }, alertDispatcher = RefreshAlertDispatcher { _, _ -> }, widgetRedrawNotifier = WidgetRedrawNotifier { })
        val result = committer.commit(RefreshRequest("acct", 0, 1, RefreshTrigger.SERVICE, 0), BalanceFetchResult.Success(UnifiedBalance(ProviderType.DEEPSEEK, "acct", true, listOf(BalanceEntry("USD", 1.0))), 10L)) { true }
        assertTrue(result is AccountRefreshResult.Committed)
    }

    private fun accountEntity() = AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED)
}
