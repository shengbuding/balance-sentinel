package com.balancesentinel.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BalanceResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize full BalanceResponse with all fields`() {
        val raw = """
            {
                "is_available": true,
                "balance_infos": [
                    {
                        "currency": "CNY",
                        "total_balance": "100.50",
                        "granted_balance": "10.00",
                        "topped_up_balance": "90.50"
                    }
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<BalanceResponse>(raw)
        assertTrue(result.isAvailable)
        assertEquals(1, result.balanceInfos.size)
        with(result.balanceInfos[0]) {
            assertEquals("CNY", currency)
            assertEquals("100.50", totalBalance)
            assertEquals("10.00", grantedBalance)
            assertEquals("90.50", toppedUpBalance)
        }
    }

    @Test
    fun `deserialize BalanceResponse with unavailable status`() {
        val raw = """
            {"is_available": false, "balance_infos": []}
        """.trimIndent()

        val result = json.decodeFromString<BalanceResponse>(raw)
        assertFalse(result.isAvailable)
        assertTrue(result.balanceInfos.isEmpty())
    }

    @Test
    fun `deserialize BalanceResponse with multiple currencies`() {
        val raw = """
            {
                "is_available": true,
                "balance_infos": [
                    {"currency": "CNY", "total_balance": "500", "granted_balance": "0", "topped_up_balance": "500"},
                    {"currency": "USD", "total_balance": "20", "granted_balance": "5", "topped_up_balance": "15"}
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<BalanceResponse>(raw)
        assertEquals(2, result.balanceInfos.size)
        assertEquals("CNY", result.balanceInfos[0].currency)
        assertEquals("USD", result.balanceInfos[1].currency)
    }

    @Test
    fun `deserialize BalanceResponse ignoring unknown fields`() {
        val raw = """
            {
                "is_available": true,
                "balance_infos": [
                    {
                        "currency": "CNY",
                        "total_balance": "100",
                        "granted_balance": "0",
                        "topped_up_balance": "100",
                        "unknown_field": "should be ignored"
                    }
                ],
                "extra_top_level": 42
            }
        """.trimIndent()

        val result = json.decodeFromString<BalanceResponse>(raw)
        assertTrue(result.isAvailable)
        assertEquals(1, result.balanceInfos.size)
    }

    @Test
    fun `serialize BalanceResponse round-trip`() {
        val original = BalanceResponse(
            isAvailable = true,
            balanceInfos = listOf(
                BalanceInfo(
                    currency = "CNY",
                    totalBalance = "250.00",
                    grantedBalance = "50.00",
                    toppedUpBalance = "200.00"
                )
            )
        )

        val encoded = json.encodeToString(BalanceResponse.serializer(), original)
        val decoded = json.decodeFromString<BalanceResponse>(encoded)

        assertEquals(original.isAvailable, decoded.isAvailable)
        assertEquals(original.balanceInfos.size, decoded.balanceInfos.size)
        assertEquals(original.balanceInfos[0].currency, decoded.balanceInfos[0].currency)
        assertEquals(original.balanceInfos[0].totalBalance, decoded.balanceInfos[0].totalBalance)
    }

    @Test
    fun `BalanceInfo equality works`() {
        val a = BalanceInfo("CNY", "100", "10", "90")
        val b = BalanceInfo("CNY", "100", "10", "90")
        val c = BalanceInfo("USD", "100", "10", "90")

        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
