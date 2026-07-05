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
}
