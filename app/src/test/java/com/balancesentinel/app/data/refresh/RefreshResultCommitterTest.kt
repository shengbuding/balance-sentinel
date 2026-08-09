package com.balancesentinel.app.data.refresh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultState
import com.balancesentinel.app.data.repository.RoomRefreshPersistence
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.CancellationException
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
    @After fun close() = com.balancesentinel.app.data.local.WalletDatabaseProvider.clearForTests()

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
        val result = runBlocking { committer.commit(RefreshRequest("acct", 0, 1, RefreshTrigger.SERVICE, 0), BalanceFetchResult.Success(UnifiedBalance(ProviderType.DEEPSEEK, "acct", true, listOf(BalanceEntry("USD", 1.0))), 10L)) { true } }
        assertTrue(result is AccountRefreshResult.Failed)
        runBlocking { assertEquals(0, db.historyDao().countRecords()); assertEquals(0, db.usageDao().countSnapshots()); assertEquals(0, db.eventLogDao().countLogs()) }
    }

    @Test fun `production committer entry writes all Room tables`() {
        runBlocking { db.accountDao().insertCreate(accountEntity()) }
        val committer = RefreshResultCommitter(context, object : RefreshAccountStore {
            override fun getAccount(accountId: String) = AccountInfo(accountId, "Primary", "key", ProviderType.DEEPSEEK, revision = 0)
            override fun getAccounts() = emptyList<AccountInfo>()
        }, roomPersistence = RoomRefreshPersistence(db), alertDispatcher = RefreshAlertDispatcher { _, _ -> }, widgetRedrawNotifier = WidgetRedrawNotifier { })
        val result = runBlocking { committer.commit(RefreshRequest("acct", 0, 1, RefreshTrigger.SERVICE, 0), BalanceFetchResult.Success(UnifiedBalance(ProviderType.DEEPSEEK, "acct", true, listOf(BalanceEntry("USD", 1.0))), 10L)) { true } }
        assertTrue(result is AccountRefreshResult.Committed)
        runBlocking { assertEquals(1, db.historyDao().countRecords()); assertEquals(1, db.usageDao().countSnapshots()); assertEquals(1, db.eventLogDao().countLogs()) }
    }

    @Test fun `default committer constructor uses installed Room database`() {
        runBlocking { db.accountDao().insertCreate(accountEntity()) }
        val committer = RefreshResultCommitter(context, object : RefreshAccountStore {
            override fun getAccount(accountId: String) = AccountInfo(accountId, "Primary", "key", ProviderType.DEEPSEEK, revision = 0)
            override fun getAccounts() = emptyList<AccountInfo>()
        }, alertDispatcher = RefreshAlertDispatcher { _, _ -> }, widgetRedrawNotifier = WidgetRedrawNotifier { })
        val result = runBlocking { committer.commit(RefreshRequest("acct", 0, 1, RefreshTrigger.SERVICE, 0), BalanceFetchResult.Success(UnifiedBalance(ProviderType.DEEPSEEK, "acct", true, listOf(BalanceEntry("USD", 1.0))), 10L)) { true } }
        assertTrue(result is AccountRefreshResult.Committed)
    }

    @Test fun `run ledger failure compensation leaves no partial Room writes`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val recorder = RoomRefreshRunRecorder(
            database = db,
            beforeResultWrite = { error("injected result-side failure") },
            clock = { 20L }
        )
        val handle = recorder.begin(
            RefreshTrigger.SERVICE,
            listOf(accountInfo()),
            startedAt = 10L,
            ownerProcessSessionId = "owner"
        )
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = accountInfo()
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { },
            runRecorder = recorder
        )

        val result = committer.commit(
            RefreshRequest("acct", 0L, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
            BalanceFetchResult.Success(balance("acct"), 15L)
        ) { true }

        assertTrue(result is AccountRefreshResult.Failed)
        assertEquals(
            RefreshAccountResultState.PERSISTENCE_FAILED,
            db.refreshRunDao().getAccountResult(handle.runId, "acct")?.state
        )
        assertEquals(0, db.historyDao().countRecords())
        assertEquals(0, db.usageDao().countSnapshots())
        assertEquals(0, db.eventLogDao().countLogs())
    }

    @Test fun `persistence failure projects cached data as stale`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val cached = listOf(
            AccountBalance(
                accountId = "acct",
                label = "Primary",
                totalBalance = "10",
                currency = "USD",
                isAvailable = true,
                grantedBalance = "",
                toppedUpBalance = "",
                lastUpdated = 77L
            )
        )
        val staleProjection: suspend (String, RefreshFailure) -> AccountRefreshResult = { accountId, failure ->
            RefreshRuntime.projectStaleFailure(accountId, failure, cached) { }
        }
        val recorder = RoomRefreshRunRecorder(
            database = db,
            beforeResultWrite = { error("injected result-side failure") },
            clock = { 20L },
            staleProjection = staleProjection
        )
        val handle = recorder.begin(
            RefreshTrigger.SERVICE,
            listOf(accountInfo()),
            startedAt = 10L,
            ownerProcessSessionId = "owner"
        )
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = accountInfo()
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { },
            runRecorder = recorder,
            staleProjection = staleProjection
        )

        val result = committer.commit(
            RefreshRequest("acct", 0L, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
            BalanceFetchResult.Success(balance("acct"), 15L)
        ) { true }

        assertTrue(result is AccountRefreshResult.Failed)
        assertTrue((result as AccountRefreshResult.Failed).stale)
        assertEquals(77L, result.dataTimestamp)
        assertEquals("Refresh data could not be saved", result.lastError)
        assertTrue(db.refreshRunDao().getAccountResult(handle.runId, "acct")?.stale == true)
    }

    @Test fun `post-commit projection failure does not downgrade durable success`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val recorder = RoomRefreshRunRecorder(database = db, clock = { 20L })
        val handle = recorder.begin(
            RefreshTrigger.SERVICE,
            listOf(accountInfo()),
            startedAt = 10L,
            ownerProcessSessionId = "owner"
        )
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = accountInfo()
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { },
            afterPersistenceWrite = { error("injected projection failure") },
            runRecorder = recorder
        )

        val result = committer.commit(
            RefreshRequest("acct", 0L, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
            BalanceFetchResult.Success(balance("acct"), 15L)
        ) { true }

        assertTrue(result is AccountRefreshResult.Committed)
        assertEquals(
            RefreshAccountResultState.SUCCEEDED,
            db.refreshRunDao().getAccountResult(handle.runId, "acct")?.state
        )
    }

    @Test fun `post-commit cancellation propagates instead of returning committed`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val recorder = RoomRefreshRunRecorder(database = db, clock = { 20L })
        val handle = recorder.begin(
            RefreshTrigger.SERVICE,
            listOf(accountInfo()),
            startedAt = 10L,
            ownerProcessSessionId = "owner"
        )
        val cancellation = CancellationException("refresh cancelled after durable commit")
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = accountInfo()
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { },
            afterPersistenceWrite = { throw cancellation },
            runRecorder = recorder
        )

        val thrown = runCatching {
            committer.commit(
                RefreshRequest("acct", 0L, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
                BalanceFetchResult.Success(balance("acct"), 15L)
            ) { true }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
        assertEquals(
            RefreshAccountResultState.SUCCEEDED,
            db.refreshRunDao().getAccountResult(handle.runId, "acct")?.state
        )
    }

    @Test fun `schema rejection records terminal ledger outcome`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val recorder = RoomRefreshRunRecorder(database = db, clock = { 20L })
        val handle = recorder.begin(
            RefreshTrigger.SERVICE,
            listOf(accountInfo()),
            startedAt = 10L,
            ownerProcessSessionId = "owner"
        )
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = accountInfo()
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { },
            runRecorder = recorder
        )

        val result = committer.commit(
            RefreshRequest("acct", 0L, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
            BalanceFetchResult.Success(
                UnifiedBalance(
                    ProviderType.DEEPSEEK,
                    "acct",
                    true,
                    listOf(BalanceEntry("USD", Double.NaN))
                ),
                15L
            )
        ) { true }

        assertTrue(result is AccountRefreshResult.Failed)
        assertEquals(
            RefreshAccountResultState.RESPONSE_INVALID,
            db.refreshRunDao().getAccountResult(handle.runId, "acct")?.state
        )
    }

    @Test fun `schema rejection preserves cached data through stale projection`() = runBlocking {
        db.accountDao().insertCreate(accountEntity())
        val recorder = RoomRefreshRunRecorder(database = db, clock = { 20L })
        val handle = recorder.begin(
            RefreshTrigger.SERVICE,
            listOf(accountInfo()),
            startedAt = 10L,
            ownerProcessSessionId = "owner"
        )
        val committer = RefreshResultCommitter(
            context = context,
            accountStore = object : RefreshAccountStore {
                override fun getAccount(accountId: String) = accountInfo()
                override fun getAccounts() = emptyList<AccountInfo>()
            },
            roomPersistence = RoomRefreshPersistence(db),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier { },
            runRecorder = recorder,
            staleProjection = { accountId, failure ->
                AccountRefreshResult.Failed(
                    accountId = accountId,
                    failure = failure,
                    stale = true,
                    dataTimestamp = 12L,
                    lastError = failure.message
                )
            }
        )

        val result = committer.commit(
            RefreshRequest("acct", 0L, 1L, RefreshTrigger.SERVICE, 10L, handle.runId),
            BalanceFetchResult.Success(
                UnifiedBalance(
                    ProviderType.DEEPSEEK,
                    "acct",
                    true,
                    listOf(BalanceEntry("USD", Double.NaN))
                ),
                15L
            )
        ) { true }

        assertTrue(result is AccountRefreshResult.Failed)
        assertTrue((result as AccountRefreshResult.Failed).stale)
        assertEquals(12L, result.dataTimestamp)
        assertEquals(
            RefreshAccountResultState.RESPONSE_INVALID,
            db.refreshRunDao().getAccountResult(handle.runId, "acct")?.state
        )
        assertTrue(db.refreshRunDao().getAccountResult(handle.runId, "acct")?.stale == true)
    }

    private fun accountInfo() = AccountInfo("acct", "Primary", "key", ProviderType.DEEPSEEK, revision = 0)

    private fun balance(accountId: String) = UnifiedBalance(
        ProviderType.DEEPSEEK,
        accountId,
        true,
        listOf(BalanceEntry("USD", 1.0))
    )

    private fun accountEntity() = AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED)
}
