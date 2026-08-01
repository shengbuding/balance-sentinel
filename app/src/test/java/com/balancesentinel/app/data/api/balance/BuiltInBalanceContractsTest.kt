package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInBalanceContractsTest {

    @Test
    fun `provider detection rejects spoofed and malformed hosts`() {
        assertNull(BalanceProviderType.detectFromUrl("https://api.deepseek.com.evil.test/v1"))
        assertNull(BalanceProviderType.detectFromUrl("not a url containing api.deepseek.com"))
        assertEquals(
            BalanceProviderType.DEEPSEEK,
            BalanceProviderType.detectFromUrl("HTTPS://API.DEEPSEEK.COM/v1")
        )
    }

    @Test
    fun `missing invalid and non finite amounts fail while explicit zero succeeds`() {
        val invalid = listOf(
            """{"is_available":true,"balance_infos":[{"currency":"CNY"}]}""",
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"oops"}]}""",
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"NaN"}]}""",
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"Infinity"}]}"""
        )

        invalid.forEach { body ->
            assertTrue(
                BuiltInBalanceContracts.deepSeek.parse(body, ProviderType.DEEPSEEK, "acct")
                    is ProviderResult.Failure
            )
        }

        val zero = BuiltInBalanceContracts.deepSeek.parse(
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"0"}]}""",
            ProviderType.DEEPSEEK,
            "acct"
        ) as ProviderResult.Success
        assertEquals(0.0, zero.data.balances.single().totalBalance, 0.0)
    }

    @Test
    fun `derived openrouter balance must remain finite`() {
        val result = BuiltInBalanceContracts.openRouter.parse(
            """{"data":{"total_credits":"1.7976931348623157E308","total_usage":"-1.7976931348623157E308"}}""",
            ProviderType.CUSTOM,
            "acct"
        )

        assertTrue(result is ProviderResult.Failure)
    }

    @Test
    fun `novita fixture converts ten thousandth dollars exactly once`() {
        val result = BuiltInBalanceContracts.novita.parse(
            resource("balance/novita.json"),
            ProviderType.CUSTOM,
            "acct"
        ) as ProviderResult.Success

        assertEquals(12.3456, result.data.balances.single().totalBalance, 0.0000001)
    }

    @Test
    fun `every built in fixture uses its strict amount and currency contract`() {
        val cases = listOf(
            FixtureCase(BuiltInBalanceContracts.deepSeek, "deepseek.json", ProviderType.DEEPSEEK, 12.5, "CNY"),
            FixtureCase(BuiltInBalanceContracts.stepFun, "stepfun.json", ProviderType.CUSTOM, 88.75, "CNY"),
            FixtureCase(BuiltInBalanceContracts.siliconFlowCn, "siliconflow.json", ProviderType.CUSTOM, 21.5, "CNY"),
            FixtureCase(BuiltInBalanceContracts.siliconFlowCom, "siliconflow.json", ProviderType.CUSTOM, 21.5, "USD"),
            FixtureCase(BuiltInBalanceContracts.openRouter, "openrouter.json", ProviderType.CUSTOM, 57.5, "USD"),
            FixtureCase(BuiltInBalanceContracts.novita, "novita.json", ProviderType.CUSTOM, 12.3456, "USD"),
            FixtureCase(BuiltInBalanceContracts.modelArk, "model_ark.json", ProviderType.MODEL_ARK, 700.0, "Token")
        )

        cases.forEach { case ->
            val result = case.contract.parse(
                resource("balance/${case.fixture}"),
                case.providerType,
                "acct"
            ) as ProviderResult.Success
            val balance = result.data.balances.single()
            assertEquals(case.amount, balance.totalBalance, 0.0000001)
            assertEquals(case.currency, balance.currency)
            assertEquals("acct", result.data.accountId)
            assertEquals(case.providerType, result.data.provider)
        }
    }

    @Test
    fun `resolver uses provider type only for native typed contracts`() {
        assertNotNull(BuiltInBalanceContracts.resolve(ProviderType.DEEPSEEK, "https://unrelated.example"))
        assertNotNull(BuiltInBalanceContracts.resolve(ProviderType.MODEL_ARK, "https://unrelated.example"))
        assertNotNull(BuiltInBalanceContracts.resolve(ProviderType.CUSTOM, "https://api.stepfun.com/v1"))
        assertNull(BuiltInBalanceContracts.resolve(ProviderType.MOONSHOT, "https://api.stepfun.com/v1"))
        assertNull(BuiltInBalanceContracts.resolve(ProviderType.CUSTOM, "https://api.stepfun.com.evil.test/v1"))
    }

    private fun resource(path: String): String {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) { "missing fixture: $path" }
        return url.readText()
    }

    private data class FixtureCase(
        val contract: BalanceContract,
        val fixture: String,
        val providerType: ProviderType,
        val amount: Double,
        val currency: String
    )
}
