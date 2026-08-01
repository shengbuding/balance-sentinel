package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            assertTrue(parse("deepSeek", body, ProviderType.DEEPSEEK) is ProviderResult.Failure)
        }

        val zero = parse(
            "deepSeek",
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"0"}]}""",
            ProviderType.DEEPSEEK
        ) as ProviderResult.Success
        assertEquals(0.0, zero.data.balances.single().totalBalance, 0.0)
    }

    @Test
    fun `novita fixture converts ten thousandth dollars exactly once`() {
        val result = parse(
            "novita",
            resource("balance/novita.json"),
            ProviderType.CUSTOM
        ) as ProviderResult.Success

        assertEquals(12.3456, result.data.balances.single().totalBalance, 0.0000001)
    }

    @Test
    fun `every built in fixture uses its strict amount and currency contract`() {
        val cases = listOf(
            FixtureCase("deepSeek", "deepseek.json", ProviderType.DEEPSEEK, 12.5, "CNY"),
            FixtureCase("stepFun", "stepfun.json", ProviderType.CUSTOM, 88.75, "CNY"),
            FixtureCase("siliconFlowCn", "siliconflow.json", ProviderType.CUSTOM, 21.5, "CNY"),
            FixtureCase("openRouter", "openrouter.json", ProviderType.CUSTOM, 57.5, "USD"),
            FixtureCase("novita", "novita.json", ProviderType.CUSTOM, 12.3456, "USD"),
            FixtureCase("modelArk", "model_ark.json", ProviderType.MODEL_ARK, 700.0, "Token")
        )

        cases.forEach { case ->
            val result = parse(
                case.contract,
                resource("balance/${case.fixture}"),
                case.providerType
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
        assertNotNull(resolve(ProviderType.DEEPSEEK, "https://unrelated.example"))
        assertNotNull(resolve(ProviderType.MODEL_ARK, "https://unrelated.example"))
        assertNotNull(resolve(ProviderType.CUSTOM, "https://api.stepfun.com/v1"))
        assertNull(resolve(ProviderType.MOONSHOT, "https://api.stepfun.com/v1"))
        assertNull(resolve(ProviderType.CUSTOM, "https://api.stepfun.com.evil.test/v1"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(
        contractName: String,
        body: String,
        providerType: ProviderType
    ): ProviderResult<UnifiedBalance> {
        val contract = contract(contractName)
        val parse = contract.javaClass.methods.single { method ->
            method.name == "parse" && method.parameterCount == 3
        }
        return parse.invoke(contract, body, providerType, "acct") as ProviderResult<UnifiedBalance>
    }

    private fun resolve(providerType: ProviderType, baseUrl: String): Any? {
        val type = contractsClass()
        val instance = type.getField("INSTANCE").get(null)
        val resolve = type.methods.single { method ->
            method.name == "resolve" && method.parameterCount == 2
        }
        return resolve.invoke(instance, providerType, baseUrl)
    }

    private fun contract(name: String): Any {
        val type = contractsClass()
        val instance = type.getField("INSTANCE").get(null)
        val getter = "get" + name.replaceFirstChar(Char::uppercaseChar)
        return type.getMethod(getter).invoke(instance)
    }

    private fun contractsClass(): Class<*> = try {
        Class.forName(
            "com.balancesentinel.app.data.api.balance.BuiltInBalanceContracts"
        )
    } catch (error: ClassNotFoundException) {
        fail("strict built-in balance contracts are not implemented")
        throw AssertionError(error)
    }

    private fun resource(path: String): String {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) { "missing fixture: $path" }
        return url.readText()
    }

    private data class FixtureCase(
        val contract: String,
        val fixture: String,
        val providerType: ProviderType,
        val amount: Double,
        val currency: String
    )
}
