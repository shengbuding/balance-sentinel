package com.balancesentinel.app.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BalanceWidgetDataStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BalanceWidgetDataStore.clearAll(context)
    }

    @After
    fun tearDown() {
        BalanceWidgetDataStore.clearAll(context)
    }

    @Test
    fun `save and retrieve single account single currency`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "My Account", "100.50", "CNY", true, "50.00", "20.00"
        )
        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(1, balances.size)
        assertEquals("acc1", balances[0].accountId)
        assertEquals("100.50", balances[0].totalBalance)
        assertEquals("CNY", balances[0].currency)
    }

    @Test
    fun `concurrent account balance saves retain every account`() {
        val writers = 24
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writers)
        repeat(writers) { index ->
            pool.submit {
                start.await()
                BalanceWidgetDataStore.saveAccountBalance(
                    context, "acc-$index", "Account $index", "1.00", "USD", true, "0", "0"
                )
            }
        }

        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(writers, BalanceWidgetDataStore.getAllBalances(context).map { it.accountId }.toSet().size)
    }

    @Test
    fun `account migration cannot overwrite an in-flight balance save`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context,
            "old",
            "Old",
            "1.00",
            "USD",
            true,
            "0",
            "0"
        )
        val migrationWriteReady = CountDownLatch(1)
        val resumeMigrationWrite = CountDownLatch(1)
        val blockingContext = BlockingWidgetWriteContext(
            context,
            migrationWriteReady,
            resumeMigrationWrite
        )
        val migrationThread = Thread(
            {
                BalanceWidgetDataStore.migrateAccountIds(
                    blockingContext,
                    mapOf("old" to "migrated")
                )
            },
            "widget-migrate"
        )
        val saveThread = Thread(
            {
                BalanceWidgetDataStore.saveAccountBalance(
                    blockingContext,
                    "saved",
                    "Saved",
                    "2.00",
                    "USD",
                    true,
                    "0",
                    "0"
                )
            },
            "widget-save"
        )

        migrationThread.start()
        assertTrue(migrationWriteReady.await(5, TimeUnit.SECONDS))
        saveThread.start()
        saveThread.join(1_000)
        resumeMigrationWrite.countDown()
        migrationThread.join(5_000)
        saveThread.join(5_000)

        assertFalse(migrationThread.isAlive)
        assertFalse(saveThread.isAlive)
        val accountIds = BalanceWidgetDataStore.getAllBalances(context)
            .map { it.accountId }
            .toSet()
        assertFalse("old" in accountIds)
        assertTrue("migrated" in accountIds)
        assertTrue("saved" in accountIds)
    }

    @Test
    fun `multi account same currency sums correctly`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "A", "100.00", "CNY", true, "0", "0"
        )
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc2", "B", "200.00", "CNY", true, "0", "0"
        )
        val agg = BalanceWidgetDataStore.getAggregatedBalance(context)
        assertNotNull(agg)
        assertEquals("300.00", agg!!.totalBalance)
        assertEquals("CNY", agg.currency)
    }

    @Test
    fun `aggregateTopTwo selects top 2 currencies by total`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "0", "0", 0),
            AccountBalance("a2", "B", "50.00", "CNY", true, "0", "0", 0),
            AccountBalance("a3", "C", "200.00", "USD", true, "0", "0", 0),
            AccountBalance("a4", "D", "10.00", "EUR", true, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals("200.00", agg!!.totalBalance)
        assertEquals("USD", agg.currency)
        assertEquals("150.00", agg.totalBalance2)
        assertEquals("CNY", agg.currency2)
    }

    @Test
    fun `zero total currencies are filtered out`() {
        val balances = listOf(
            AccountBalance("a1", "A", "0.00", "CNY", true, "0", "0", 0),
            AccountBalance("a2", "B", "100.00", "USD", true, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals("100.00", agg!!.totalBalance)
        assertEquals("USD", agg.currency)
        assertEquals("", agg.totalBalance2)
    }

    @Test
    fun `accountId currency double key prevents overwrite across currencies`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "Same Acc", "100.00", "CNY", true, "0", "0"
        )
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "Same Acc", "200.00", "USD", true, "0", "0"
        )
        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(2, balances.size)
        assertTrue(balances.any { it.currency == "CNY" && it.totalBalance == "100.00" })
        assertTrue(balances.any { it.currency == "USD" && it.totalBalance == "200.00" })
    }

    @Test
    fun `update existing balance replaces not appends`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "A", "100.00", "CNY", true, "0", "0"
        )
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "A", "200.00", "CNY", false, "0", "0"
        )
        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(1, balances.size)
        assertEquals("200.00", balances[0].totalBalance)
        assertFalse(balances[0].isAvailable)
    }

    @Test
    fun `all zero balances falls back to first currency`() {
        val balances = listOf(
            AccountBalance("a1", "A", "0.00", "CNY", true, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals("0.00", agg!!.totalBalance)
        assertEquals("CNY", agg.currency)
    }

    // ═══════════════════════════════════════════════════════════
    // aggregateTopTwo — edge cases
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `aggregateTopTwo empty list returns null`() {
        val agg = BalanceWidgetDataStore.aggregateTopTwo(emptyList())
        assertNull(agg)
    }

    @Test
    fun `aggregateTopTwo single non-zero currency no secondary`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "30.00", "20.00", 1000L),
            AccountBalance("a2", "B", "50.00", "CNY", true, "10.00", "5.00", 2000L)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals("150.00", agg!!.totalBalance)
        assertEquals("CNY", agg.currency)
        assertEquals("", agg.totalBalance2)   // no secondary currency
        assertEquals("", agg.currency2)
        assertEquals("40.00", agg.grantedBalance)
        assertEquals("25.00", agg.toppedUpBalance)
    }

    @Test
    fun `aggregateTopTwo includes granted and toppedUp for primary currency`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "USD", true, "30.00", "20.00", 1000L),
            AccountBalance("a2", "B", "200.00", "CNY", true, "50.00", "10.00", 2000L)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        // Primary should be CNY (200 > 100)
        assertEquals("200.00", agg!!.totalBalance)
        assertEquals("CNY", agg.currency)
        assertEquals("50.00", agg.grantedBalance)
        assertEquals("10.00", agg.toppedUpBalance)
    }

    @Test
    fun `aggregateTopTwo accountCount counts distinct accounts`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "0", "0", 0),
            AccountBalance("a1", "A", "50.00", "USD", true, "0", "0", 0),  // same account, diff currency
            AccountBalance("a2", "B", "200.00", "EUR", true, "0", "0", 0),
            AccountBalance("a3", "C", "75.00", "CNY", true, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals(3, agg!!.accountCount)  // a1, a2, a3 = 3 distinct accounts
    }

    @Test
    fun `aggregateTopTwo lastUpdated is max of all entries`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "0", "0", 1000L),
            AccountBalance("a2", "B", "200.00", "USD", true, "0", "0", 5000L),
            AccountBalance("a3", "C", "50.00", "CNY", true, "0", "0", 3000L)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals(5000L, agg!!.lastUpdated)
    }

    @Test
    fun `aggregateTopTwo handles invalid balance strings gracefully`() {
        val balances = listOf(
            AccountBalance("a1", "A", "not-a-number", "CNY", true, "0", "0", 0),
            AccountBalance("a2", "B", "200.00", "USD", true, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        // "not-a-number" → 0.0, so CNY is filtered (0.0); USD (200) is primary
        assertEquals("200.00", agg!!.totalBalance)
        assertEquals("USD", agg.currency)
    }

    @Test
    fun `aggregateTopTwo isAvailable false when any balance unavailable`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "0", "0", 0),
            AccountBalance("a2", "B", "200.00", "USD", false, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertFalse(agg!!.isAvailable)
    }

    @Test
    fun `aggregateTopTwo isAvailable true when all balances available`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "0", "0", 0),
            AccountBalance("a2", "B", "200.00", "USD", true, "0", "0", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertTrue(agg!!.isAvailable)
    }

    @Test
    fun `aggregateTopTwo handles granted and toppedUp as non-numeric strings`() {
        val balances = listOf(
            AccountBalance("a1", "A", "100.00", "CNY", true, "N/A", "N/A", 0),
            AccountBalance("a2", "B", "50.00", "CNY", true, "N/A", "N/A", 0)
        )
        val agg = BalanceWidgetDataStore.aggregateTopTwo(balances)
        assertNotNull(agg)
        assertEquals("150.00", agg!!.totalBalance)
        assertEquals("0.00", agg.grantedBalance)   // N/A → 0.0
        assertEquals("0.00", agg.toppedUpBalance)  // N/A → 0.0
    }

    // ═══════════════════════════════════════════════════════════
    // removeAccountBalance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `removeAccountBalance removes matching account`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "A", "100.00", "CNY", true, "0", "0"
        )
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc2", "B", "200.00", "USD", true, "0", "0"
        )
        BalanceWidgetDataStore.removeAccountBalance(context, "acc1")

        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(1, balances.size)
        assertEquals("acc2", balances[0].accountId)
    }

    @Test
    fun `removeAccountBalance of non-existent account is no-op`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "A", "100.00", "CNY", true, "0", "0"
        )
        BalanceWidgetDataStore.removeAccountBalance(context, "nonexistent")

        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertEquals(1, balances.size)
    }

    @Test
    fun `removeAccountBalance handles empty store`() {
        // Should not throw on empty store
        BalanceWidgetDataStore.removeAccountBalance(context, "acc1")
        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `failure without a cached balance is durable`() {
        BalanceWidgetDataStore.markAccountStale(context, "acc1", "TLS pin failure")

        val snapshot = BalanceWidgetDataStore.getSnapshot(context)
        assertTrue(snapshot.balances.isEmpty())
        assertEquals(
            AccountBalanceFailure("acc1", "TLS pin failure", snapshot.failures.single().failedAt),
            snapshot.failures.single()
        )
    }

    @Test
    fun `snapshot observer publishes failure and following success atomically`() = runBlocking {
        val updates = Channel<BalanceCacheSnapshot>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            BalanceWidgetDataStore.observeSnapshot(context).collect(updates::send)
        }
        assertEquals(BalanceCacheSnapshot(emptyList(), emptyList()), withTimeout(2_000) { updates.receive() })

        BalanceWidgetDataStore.markAccountStale(context, "acc1", "offline")
        ShadowLooper.idleMainLooper()
        val failed = withTimeout(2_000) { updates.receive() }

        assertEquals("offline", failed.failures.single().reason)
        assertTrue(failed.balances.isEmpty())

        BalanceWidgetDataStore.replaceAccountBalances(
            context,
            "acc1",
            listOf(AccountBalance("acc1", "A", "12.00", "USD", true, "0", "0", 42L))
        )
        ShadowLooper.idleMainLooper()
        val succeeded = withTimeout(2_000) { updates.receive() }

        assertEquals("12.00", succeeded.balances.single().totalBalance)
        assertTrue(succeeded.failures.isEmpty())
        collection.cancelAndJoin()
    }

    @Test
    fun `failure follows account migration and is removed with the account`() {
        BalanceWidgetDataStore.markAccountStale(context, "old", "offline")

        BalanceWidgetDataStore.migrateAccountIds(context, mapOf("old" to "new"))
        val migrated = BalanceWidgetDataStore.getSnapshot(context)
        assertEquals("new", migrated.failures.single().accountId)

        BalanceWidgetDataStore.removeAccountBalance(context, "new")
        assertTrue(BalanceWidgetDataStore.getSnapshot(context).failures.isEmpty())
    }

    @Test
    fun `clear removes balances and failures together`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "balance", "Balance", "10.00", "USD", true, "0", "0"
        )
        BalanceWidgetDataStore.markAccountStale(context, "failure", "offline")

        BalanceWidgetDataStore.clearAll(context)

        assertEquals(BalanceCacheSnapshot(emptyList(), emptyList()), BalanceWidgetDataStore.getSnapshot(context))
    }

    // ═══════════════════════════════════════════════════════════
    // getAggregatedBalance — empty store
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getAggregatedBalance returns null when store is empty`() {
        val agg = BalanceWidgetDataStore.getAggregatedBalance(context)
        assertNull(agg)
    }

    // ═══════════════════════════════════════════════════════════
    // getAllBalances — corrupted JSON recovery
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getAllBalances returns empty for corrupted JSON`() {
        val prefs = context.getSharedPreferences("widget_balance_cache", Context.MODE_PRIVATE)
        prefs.edit().putString("account_balances", "this-is-not-json").apply()

        val balances = BalanceWidgetDataStore.getAllBalances(context)
        assertTrue(balances.isEmpty())
    }

    private class BlockingWidgetWriteContext(
        base: Context,
        private val writeReady: CountDownLatch,
        private val resumeWrite: CountDownLatch
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != "widget_balance_cache") return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?
                        ): SharedPreferences.Editor {
                            if (
                                key == "account_balances" &&
                                Thread.currentThread().name == "widget-migrate"
                            ) {
                                writeReady.countDown()
                                check(resumeWrite.await(5, TimeUnit.SECONDS))
                            }
                            editor.putString(key, value)
                            return this
                        }
                    }
                }
            }
        }
    }
}
