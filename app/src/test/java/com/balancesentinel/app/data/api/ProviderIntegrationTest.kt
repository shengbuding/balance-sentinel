package com.balancesentinel.app.data.api

import org.junit.Assert.*
import org.junit.Test

/**
 * 供应商集成测试
 * 测试ProviderFactory和缓存层的集成
 */
class ProviderIntegrationTest {

    @Test
    fun testProviderFactoryReturnsAllProviders() {
        val providers = listOf(
            ProviderType.DEEPSEEK,
            ProviderType.MOONSHOT,
            ProviderType.DOUBAO,
            ProviderType.BAICHUAN,
            ProviderType.QWEN,
            ProviderType.ZHIPU,
            ProviderType.WENXIN,
            ProviderType.OPENAI,
            ProviderType.ANTHROPIC,
            ProviderType.GEMINI,
            ProviderType.MISTRAL,
            ProviderType.COHERE
        )

        providers.forEach { type ->
            val provider = ProviderFactory.get(type)
            assertNotNull("Provider for $type should not be null", provider)
            assertEquals("Provider type should match", type, provider.providerType)
        }
    }

    @Test
    fun testProviderFactoryCachesInstances() {
        val type = ProviderType.DEEPSEEK

        val provider1 = ProviderFactory.get(type)
        val provider2 = ProviderFactory.get(type)

        assertSame("Provider instances should be cached", provider1, provider2)
    }

    @Test
    fun testProviderFactoryThrowsForCustomWithoutRegistration() {
        try {
            ProviderFactory.get(ProviderType.CUSTOM)
            fail("Should throw exception for custom provider without registration")
        } catch (e: IllegalArgumentException) {
            // Expected
            assertTrue("Error message should mention custom provider",
                e.message?.contains("自定义供应商") == true)
        }
    }

    @Test
    fun testProviderFactoryRegisterCustomProvider() {
        val customProvider = object : AiProvider {
            override val providerType = ProviderType.CUSTOM
            override val displayName = "Custom Provider"

            override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
                return ProviderResult.Success(
                    UnifiedBalance(
                        provider = ProviderType.CUSTOM,
                        accountId = "test",
                        isAvailable = true,
                        balances = listOf(
                            BalanceEntry(currency = "CNY", totalBalance = 100.0)
                        )
                    )
                )
            }
        }

        ProviderFactory.register(ProviderType.CUSTOM, customProvider)

        val provider = ProviderFactory.get(ProviderType.CUSTOM)
        assertNotNull("Custom provider should be registered", provider)
        assertEquals("Custom provider type should match", ProviderType.CUSTOM, provider.providerType)
    }

    @Test
    fun testProviderResultSuccess() {
        val balance = UnifiedBalance(
            provider = ProviderType.DEEPSEEK,
            accountId = "test",
            isAvailable = true,
            balances = listOf(
                BalanceEntry(currency = "CNY", totalBalance = 100.0)
            )
        )

        val result = ProviderResult.Success(balance)

        assertTrue("Result should be success", result is ProviderResult.Success)
        assertEquals("Balance should match", balance, result.getOrNull())
        assertEquals("Balance should match", balance, result.getOrThrow())
    }

    @Test
    fun testProviderResultFailure() {
        val error = ProviderError.AuthError(ProviderType.DEEPSEEK, "Invalid API key")
        val result = ProviderResult.Failure(error)

        assertTrue("Result should be failure", result is ProviderResult.Failure)
        assertNull("getOrNull should return null", result.getOrNull())

        try {
            result.getOrThrow()
            fail("Should throw exception")
        } catch (e: IllegalStateException) {
            assertEquals("Error message should match", "Invalid API key", e.message)
        }
    }

    @Test
    fun testProviderResultMap() {
        val balance = UnifiedBalance(
            provider = ProviderType.DEEPSEEK,
            accountId = "test",
            isAvailable = true,
            balances = listOf(
                BalanceEntry(currency = "CNY", totalBalance = 100.0)
            )
        )

        val result = ProviderResult.Success(balance)
        val mapped = result.map { it.balances.size }

        assertTrue("Mapped result should be success", mapped is ProviderResult.Success)
        assertEquals("Mapped value should be 1", 1, (mapped as ProviderResult.Success).data)
    }

    @Test
    fun testProviderResultMapFailure() {
        val error = ProviderError.AuthError(ProviderType.DEEPSEEK, "Invalid API key")
        val result = ProviderResult.Failure(error)
        val mapped = result.map { "transformed" }

        assertTrue("Mapped result should still be failure", mapped is ProviderResult.Failure)
    }

    @Test
    fun testProviderConfigApiKey() {
        val config = ProviderConfig(
            providerType = ProviderType.DEEPSEEK,
            credentials = mapOf("apiKey" to "test-key-123"),
            settings = emptyMap()
        )

        assertEquals("API key should match", "test-key-123", config.apiKey)
    }

    @Test
    fun testProviderConfigOptionalFields() {
        val config = ProviderConfig(
            providerType = ProviderType.DEEPSEEK,
            credentials = mapOf(
                "apiKey" to "test-key-123",
                "secretKey" to "secret-123",
                "orgId" to "org-123"
            ),
            settings = mapOf("baseUrl" to "https://custom.api.com")
        )

        assertEquals("API key should match", "test-key-123", config.apiKey)
        assertEquals("Secret key should match", "secret-123", config.secretKey)
        assertEquals("Org ID should match", "org-123", config.orgId)
        assertEquals("Base URL should match", "https://custom.api.com", config.baseUrl)
    }
}
