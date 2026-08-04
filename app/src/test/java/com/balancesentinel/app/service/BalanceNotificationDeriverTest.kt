package com.balancesentinel.app.service

import com.balancesentinel.app.widget.AccountBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceNotificationDeriverTest {

    // Mutation caught: selecting one wallet instead of aggregating all committed rows by currency.
    @Test
    fun `derivation aggregates top two currencies from all committed wallets`() {
        val result = BalanceNotificationDeriver.derive(
            committedBalances = committedBalances(),
            walletOrder = savedOrder(),
            showTotal = true
        )

        assertNotNull(result)
        checkNotNull(result)
        assertEquals("1100.00", result.totalBalance)
        assertEquals("CNY", result.totalCurrency)
        assertEquals("200.00", result.totalBalance2)
        assertEquals("USD", result.totalCurrency2)
        assertFalse(result.isAvailable)
    }

    // Mutation caught: retaining committed-cache iteration order or ignoring total placement.
    @Test
    fun `derivation applies saved wallet selection order and total position`() {
        val result = checkNotNull(
            BalanceNotificationDeriver.derive(
                committedBalances = committedBalances(),
                walletOrder = savedOrder(),
                showTotal = true
            )
        )

        assertEquals(listOf("usd200", "cny600"), result.wallets.map(AccountBalance::accountId))
        assertTrue(result.showTotal)
        assertEquals(1, result.totalPosition)
    }

    private fun committedBalances() = listOf(
        AccountBalance("cny600", "CNY 600", "600.00", "CNY", true, "0", "0", 1L),
        AccountBalance("cny500", "CNY 500", "500.00", "CNY", false, "0", "0", 2L),
        AccountBalance("usd200", "USD 200", "200.00", "USD", true, "0", "0", 3L)
    )

    private fun savedOrder() = listOf(
        "usd200_USD",
        "__total__",
        "cny600_CNY"
    )
}
