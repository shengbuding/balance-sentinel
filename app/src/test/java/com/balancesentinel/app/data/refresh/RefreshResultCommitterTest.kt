package com.balancesentinel.app.data.refresh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.StoreWriteResult
import com.balancesentinel.app.data.repository.UsageDataStore
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshResultCommitterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearStores(context)
    }

    @After
    fun tearDown() {
        clearStores(context)
    }

    // Mutation caught: publishing a new in-memory cache value before its durable commit succeeds.
    @Test
    fun `provider cache persistence failure preserves the previously committed balance`() {
        val committed = balance(5.0)
        ProviderCache(context).put(ProviderType.DEEPSEEK, ACCOUNT_ID, committed)
        val failingCache = ProviderCache(FailingProviderCacheContext(context))

        assertThrows(IllegalStateException::class.java) {
            failingCache.put(ProviderType.DEEPSEEK, ACCOUNT_ID, balance(99.0))
        }

        val retained = ProviderCache(context).get(ProviderType.DEEPSEEK, ACCOUNT_ID)
        assertEquals(5.0, retained!!.balances.single().totalBalance, 0.0)
    }

    // Mutation caught: moving any persistent step, alert, or redraw outside the required order.
    @Test
    fun `successful commit replaces widget account data and performs side effects in order`() {
        val events = mutableListOf<String>()
        val recordingContext = RecordingPrefsContext(context, events)
        val accountStore = MutableAccountStore(listOf(account(revision = 2)))
        BalanceWidgetDataStore.saveAccountBalance(
            context, ACCOUNT_ID, "Old", "1.0", "OLD", true, "", ""
        )
        BalanceWidgetDataStore.saveAccountBalance(
            context, "other", "Other", "3.0", "EUR", true, "", ""
        )
        events.clear()
        val committer = RefreshResultCommitter(
            context = recordingContext,
            accountStore = accountStore,
            providerCache = ProviderCache(recordingContext),
            alertDispatcher = RefreshAlertDispatcher { current, entry ->
                events += "alerts:${current.id}:${entry.currency}"
            },
            widgetRedrawNotifier = WidgetRedrawNotifier { events += "widget-redraw" }
        )

        val result = committer.commit(
            request(revision = 2, trigger = RefreshTrigger.MANUAL_ACCOUNT),
            success(20.0, 7.0, completedAt = 5_000L),
            isLatest = { true }
        )

        assertTrue(result is AccountRefreshResult.Committed)
        assertEquals(
            listOf(
                "provider_cache",
                "widget_balance_cache",
                "raw_records",
                "refresh_log_store",
                "usage_snapshots",
                "alerts:$ACCOUNT_ID:CNY",
                "alerts:$ACCOUNT_ID:USD",
                "widget-redraw"
            ),
            events
        )
        val widgetBalances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(setOf("CNY", "USD"), widgetBalances.filter { it.accountId == ACCOUNT_ID }
            .map { it.currency }.toSet())
        assertTrue(widgetBalances.none { it.accountId == ACCOUNT_ID && it.currency == "OLD" })
        assertTrue(widgetBalances.any { it.accountId == "other" && it.currency == "EUR" })

        val records = RawRecordStore.getAllRecords(context).filter { it.accountId == ACCOUNT_ID }
        assertEquals(2, records.size)
        assertTrue(records.all { it.timestamp == 5_000L })
        assertEquals(2, RefreshLogStore.getEntries(context).size)
        assertEquals(
            listOf(5_000L),
            UsageDataStore.getAllSnapshots(context).filter { it.accountId == ACCOUNT_ID }
                .map { it.timestamp }
        )
    }

    // Mutation caught: using fetched.completedAt, request.startedAt, or one clock read per currency.
    @Test
    fun `raw batch reads wall clock once immediately before construction and keeps other timestamps`() {
        val events = mutableListOf<String>()
        val recordingContext = RecordingPrefsContext(context, events)
        var clockReads = 0
        val committer = RefreshResultCommitter(
            context = recordingContext,
            accountStore = MutableAccountStore(listOf(account(revision = 2))),
            providerCache = ProviderCache(recordingContext),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> },
            widgetRedrawNotifier = WidgetRedrawNotifier {},
            wallClock = {
                clockReads += 1
                events += "raw-wall-clock"
                9_000L
            }
        )

        val result = committer.commit(
            request(revision = 2),
            success(20.0, 7.0, completedAt = 5_000L),
            isLatest = { true }
        )

        assertTrue(result is AccountRefreshResult.Committed)
        assertEquals(1, clockReads)
        assertEquals(
            listOf(
                "provider_cache",
                "widget_balance_cache",
                "raw-wall-clock",
                "raw_records",
                "refresh_log_store",
                "usage_snapshots"
            ),
            events
        )
        assertTrue(RawRecordStore.getAllRecords(context).all { it.timestamp == 9_000L })
        assertTrue(BalanceWidgetDataStore.getAllBalances(context).all { it.lastUpdated == 5_000L })
        assertTrue(RefreshLogStore.getEntries(context).all { it.timestamp == 5_000L })
        assertEquals(listOf(5_000L), UsageDataStore.getAllSnapshots(context).map { it.timestamp })
    }

    // Mutation caught: treating a returned raw write failure as success because no exception was thrown.
    @Test
    fun `reported raw write failure rolls back earlier state and stops later side effects`() {
        val priorBalance = balance(5.0)
        ProviderCache(context).put(ProviderType.DEEPSEEK, ACCOUNT_ID, priorBalance)
        BalanceWidgetDataStore.saveAccountBalance(
            context, ACCOUNT_ID, "Prior", "5.0", "OLD", true, "", ""
        )
        val priorRecord = RawRecord("other", 1L, "EUR", 3f, 0f, 0f)
        RawRecordStore.addRecords(context, listOf(priorRecord))
        val events = mutableListOf<String>()
        val recordingContext = RecordingPrefsContext(context, events)
        val committer = RefreshResultCommitter(
            context = recordingContext,
            accountStore = MutableAccountStore(listOf(account(revision = 2))),
            providerCache = ProviderCache(recordingContext),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> events += "alerts" },
            widgetRedrawNotifier = WidgetRedrawNotifier { events += "widget-redraw" },
            wallClock = { 9_000L },
            rawRecordWriter = { _, _ ->
                events += "raw-writer-failed"
                StoreWriteResult.Failed("ADD_RECORDS", "Raw record write failed")
            }
        )

        val result = committer.commit(
            request(revision = 2),
            success(99.0, completedAt = 5_000L),
            isLatest = { true }
        )

        assertTrue(result is AccountRefreshResult.Failed)
        assertTrue((result as AccountRefreshResult.Failed).failure is RefreshFailure.PersistenceFailure)
        assertEquals(
            5.0,
            ProviderCache(context).get(ProviderType.DEEPSEEK, ACCOUNT_ID)!!
                .balances.single().totalBalance,
            0.0
        )
        assertEquals(listOf("OLD"), BalanceWidgetDataStore.getAllBalances(context)
            .filter { it.accountId == ACCOUNT_ID }.map { it.currency })
        assertEquals(listOf(priorRecord), RawRecordStore.getAllRecords(context))
        assertTrue(RefreshLogStore.getEntries(context).isEmpty())
        assertTrue(UsageDataStore.getAllSnapshots(context).isEmpty())
        assertTrue("raw-writer-failed" in events)
        assertTrue("alerts" !in events)
        assertTrue("widget-redraw" !in events)
    }

    // Mutation caught: checking only account ID and allowing a result from an older revision to write.
    @Test
    fun `edited account revision rejects an in flight result without side effects`() {
        val events = mutableListOf<String>()
        val recordingContext = RecordingPrefsContext(context, events)
        val committer = RefreshResultCommitter(
            context = recordingContext,
            accountStore = MutableAccountStore(listOf(account(revision = 3))),
            providerCache = ProviderCache(recordingContext),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> events += "alerts" },
            widgetRedrawNotifier = WidgetRedrawNotifier { events += "widget-redraw" }
        )

        val result = committer.commit(
            request(revision = 2),
            success(99.0, completedAt = 100L),
            isLatest = { true }
        )

        assertTrue(result is AccountRefreshResult.Stale)
        assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
        assertTrue(BalanceWidgetDataStore.getAllBalances(context).isEmpty())
        assertNull(ProviderCache(context).get(ProviderType.DEEPSEEK, ACCOUNT_ID))
        assertTrue(events.isEmpty())
    }

    // Mutation caught: writing a token-invalidated result because revision still matches.
    @Test
    fun `stale token rejects the result before any persistence`() {
        val events = mutableListOf<String>()
        val recordingContext = RecordingPrefsContext(context, events)
        val committer = RefreshResultCommitter(
            recordingContext,
            MutableAccountStore(listOf(account(revision = 2))),
            ProviderCache(recordingContext)
        )

        val result = committer.commit(
            request(revision = 2),
            success(50.0, completedAt = 100L),
            isLatest = { false }
        )

        assertTrue(result is AccountRefreshResult.Stale)
        assertTrue(events.isEmpty())
        assertNull(ProviderCache(context).get(ProviderType.DEEPSEEK, ACCOUNT_ID))
    }

    // Mutation caught: returning failure after a mid-commit write without restoring earlier stores.
    @Test
    fun `mid commit persistence failure restores prior state and stops downstream effects`() {
        val priorBalance = balance(5.0)
        ProviderCache(context).put(ProviderType.DEEPSEEK, ACCOUNT_ID, priorBalance)
        BalanceWidgetDataStore.saveAccountBalance(
            context, ACCOUNT_ID, "Prior", "5.0", "OLD", true, "", ""
        )
        val priorRecord = RawRecord("other", 1L, "EUR", 3f, 0f, 0f)
        RawRecordStore.addRecords(context, listOf(priorRecord))
        val priorLog = RefreshLogEntry(
            id = 1L,
            type = RefreshLogType.AUTO,
            totalBalance = "3.0",
            currency = "EUR",
            timestamp = 1L
        )
        RefreshLogStore.addEntries(context, listOf(priorLog))
        val priorUsage = UsageSnapshot("other", 1L)
        UsageDataStore.saveSnapshot(context, priorUsage)

        val events = mutableListOf<String>()
        val failingContext = RecordingPrefsContext(
            context,
            events,
            failNextCommitFor = "refresh_log_store"
        )
        val committer = RefreshResultCommitter(
            context = failingContext,
            accountStore = MutableAccountStore(listOf(account(revision = 2))),
            providerCache = ProviderCache(failingContext),
            alertDispatcher = RefreshAlertDispatcher { _, _ -> events += "alerts" },
            widgetRedrawNotifier = WidgetRedrawNotifier { events += "widget-redraw" }
        )

        val result = committer.commit(
            request(revision = 2),
            success(99.0, completedAt = 100L),
            isLatest = { true }
        )

        assertTrue(result is AccountRefreshResult.Failed)
        assertTrue((result as AccountRefreshResult.Failed).failure is RefreshFailure.PersistenceFailure)
        assertEquals(
            5.0,
            ProviderCache(context).get(ProviderType.DEEPSEEK, ACCOUNT_ID)!!
                .balances.single().totalBalance,
            0.0
        )
        assertEquals(listOf("OLD"), BalanceWidgetDataStore.getAllBalances(context)
            .filter { it.accountId == ACCOUNT_ID }.map { it.currency })
        assertEquals(listOf(priorRecord), RawRecordStore.getAllRecords(context))
        assertEquals(listOf(priorLog), RefreshLogStore.getEntries(context))
        assertEquals(listOf(priorUsage), UsageDataStore.getAllSnapshots(context))
        assertTrue("alerts" !in events)
        assertTrue("widget-redraw" !in events)
    }

    private fun account(revision: Long) = AccountInfo(
        id = ACCOUNT_ID,
        label = "Primary",
        apiKey = "api-key-123456",
        providerType = ProviderType.DEEPSEEK,
        revision = revision
    )

    private fun request(
        revision: Long,
        trigger: RefreshTrigger = RefreshTrigger.SERVICE
    ) = RefreshRequest(
        accountId = ACCOUNT_ID,
        revision = revision,
        token = 7L,
        trigger = trigger,
        startedAt = 10L
    )

    private fun success(
        cny: Double,
        usd: Double? = null,
        completedAt: Long
    ) = BalanceFetchResult.Success(
        balance = UnifiedBalance(
            provider = ProviderType.DEEPSEEK,
            accountId = ACCOUNT_ID,
            isAvailable = true,
            balances = buildList {
                add(BalanceEntry("CNY", cny, cny / 2, cny / 4))
                if (usd != null) add(BalanceEntry("USD", usd, usd / 2, usd / 4))
            }
        ),
        completedAt = completedAt
    )

    private fun balance(amount: Double) = UnifiedBalance(
        provider = ProviderType.DEEPSEEK,
        accountId = ACCOUNT_ID,
        isAvailable = true,
        balances = listOf(BalanceEntry("CNY", amount))
    )

    private fun clearStores(target: Context) {
        RawRecordStore.clear(target)
        RefreshLogStore.clear(target)
        UsageDataStore.clear(target)
        BalanceWidgetDataStore.clearAll(target)
        ProviderCache(target).clearAll()
    }

    private class MutableAccountStore(
        private var accounts: List<AccountInfo>
    ) : RefreshAccountStore {
        override fun getAccount(accountId: String): AccountInfo? =
            accounts.find { it.id == accountId }

        override fun getAccounts(): List<AccountInfo> = accounts.toList()
    }

    private class RecordingPrefsContext(
        base: Context,
        private val events: MutableList<String>,
        private val failNextCommitFor: String? = null
    ) : ContextWrapper(base) {
        private var failed = false

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?
                        ): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun remove(key: String?): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }

                        override fun clear(): SharedPreferences.Editor {
                            editor.clear()
                            return this
                        }

                        override fun commit(): Boolean {
                            events += name
                            if (!failed && name == failNextCommitFor) {
                                failed = true
                                editor.commit()
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }

    private class FailingProviderCacheContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            if (name != "provider_cache") return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?
                        ): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun commit(): Boolean = false
                    }
                }
            }
        }
    }

    private companion object {
        const val ACCOUNT_ID = "acct"
    }
}
