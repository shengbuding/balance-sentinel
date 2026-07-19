package com.balancesentinel.app.data.api

import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * 供应商性能测试
 */
class ProviderPerformanceTest {

    @Test
    fun testProviderFactoryPerformance() {
        val iterations = 1000
        val time = measureTimeMillis {
            repeat(iterations) {
                ProviderFactory.get(ProviderType.DEEPSEEK)
            }
        }

        println("ProviderFactory.get() x $iterations: ${time}ms")
        assertTrue("ProviderFactory should be fast (< 100ms for 1000 calls)", time < 100)
    }

    @Test
    fun testProviderTypeLookupPerformance() {
        val iterations = 10000
        val time = measureTimeMillis {
            repeat(iterations) {
                ProviderType.fromId("deepseek")
            }
        }

        println("ProviderType.fromId() x $iterations: ${time}ms")
        assertTrue("ProviderType lookup should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderConfigCreationPerformance() {
        val iterations = 10000
        val time = measureTimeMillis {
            repeat(iterations) {
                ProviderConfig(
                    providerType = ProviderType.DEEPSEEK,
                    credentials = mapOf("apiKey" to "test-key"),
                    settings = emptyMap()
                )
            }
        }

        println("ProviderConfig creation x $iterations: ${time}ms")
        assertTrue("ProviderConfig creation should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderResultCreationPerformance() {
        val iterations = 10000
        val time = measureTimeMillis {
            repeat(iterations) {
                ProviderResult.Success(
                    UnifiedBalance(
                        provider = ProviderType.DEEPSEEK,
                        accountId = "test",
                        isAvailable = true,
                        balances = listOf(
                            BalanceEntry(currency = "CNY", totalBalance = 100.0)
                        )
                    )
                )
            }
        }

        println("ProviderResult creation x $iterations: ${time}ms")
        assertTrue("ProviderResult creation should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderErrorCreationPerformance() {
        val iterations = 10000
        val time = measureTimeMillis {
            repeat(iterations) {
                ProviderError.AuthError(ProviderType.DEEPSEEK, "Test error")
            }
        }

        println("ProviderError creation x $iterations: ${time}ms")
        assertTrue("ProviderError creation should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderTypeEnumPerformance() {
        val iterations = 100000
        val time = measureTimeMillis {
            repeat(iterations) {
                ProviderType.entries.forEach { type ->
                    type.id
                    type.displayName
                }
            }
        }

        println("ProviderType enum access x $iterations: ${time}ms")
        assertTrue("ProviderType enum access should be fast (< 100ms for 100000 calls)", time < 100)
    }

    @Test
    fun testMultipleProviderTypesPerformance() {
        val types = listOf(
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

        val iterations = 1000
        val time = measureTimeMillis {
            repeat(iterations) {
                types.forEach { type ->
                    ProviderFactory.get(type)
                }
            }
        }

        println("All providers access x $iterations: ${time}ms")
        assertTrue("All providers access should be fast (< 500ms for 1000 calls)", time < 500)
    }
}
