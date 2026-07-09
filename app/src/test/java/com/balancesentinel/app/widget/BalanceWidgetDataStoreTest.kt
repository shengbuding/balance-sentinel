package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
