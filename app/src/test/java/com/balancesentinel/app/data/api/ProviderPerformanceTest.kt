package com.balancesentinel.app.data.api

import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * 供应商性能测试
 *
 * 这些断言是 wall-clock 计时，对 JIT 预热与机器负载非常敏感：首次调用会构造 provider（含
 * 网络客户端与 TLS 初始化），单次 GC 暂停也会让测量值翻倍。因此每个用例都取多轮测量的最小值，
 * 避免把一次性开销或偶发暂停算作性能回归。
 */
class ProviderPerformanceTest {

    /** 取 runs 轮测量中的最小值（毫秒）。 */
    private fun bestOf(runs: Int = 3, block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(runs) {
            val elapsed = measureTimeMillis(block)
            if (elapsed < best) best = elapsed
        }
        return best
    }

    @Test
    fun testProviderFactoryPerformance() {
        val iterations = 1000
        val time = bestOf {
            repeat(iterations) {
                ProviderFactory.get(ProviderType.DEEPSEEK)
            }
        }

        println("ProviderFactory.get() x ${iterations}: ${time}ms")
        assertTrue("ProviderFactory should be fast (< 100ms for 1000 calls)", time < 100)
    }

    @Test
    fun testProviderTypeLookupPerformance() {
        val iterations = 10000
        val time = bestOf {
            repeat(iterations) {
                ProviderType.fromId("deepseek")
            }
        }

        println("ProviderType.fromId() x ${iterations}: ${time}ms")
        assertTrue("ProviderType lookup should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderConfigCreationPerformance() {
        val iterations = 10000
        val time = bestOf {
            repeat(iterations) {
                ProviderConfig(
                    providerType = ProviderType.DEEPSEEK,
                    credentials = mapOf("apiKey" to "test-key"),
                    settings = emptyMap()
                )
            }
        }

        println("ProviderConfig creation x ${iterations}: ${time}ms")
        assertTrue("ProviderConfig creation should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderResultCreationPerformance() {
        val iterations = 10000
        val time = bestOf {
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

        println("ProviderResult creation x ${iterations}: ${time}ms")
        assertTrue("ProviderResult creation should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderErrorCreationPerformance() {
        val iterations = 10000
        val time = bestOf {
            repeat(iterations) {
                ProviderError.AuthError(ProviderType.DEEPSEEK, "Test error")
            }
        }

        println("ProviderError creation x ${iterations}: ${time}ms")
        assertTrue("ProviderError creation should be fast (< 50ms for 10000 calls)", time < 50)
    }

    @Test
    fun testProviderTypeEnumPerformance() {
        val iterations = 100000
        val time = bestOf {
            repeat(iterations) {
                ProviderType.entries.forEach { type ->
                    type.id
                    type.displayName
                }
            }
        }

        println("ProviderType enum access x ${iterations}: ${time}ms")
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
        val time = bestOf {
            repeat(iterations) {
                types.forEach { type ->
                    ProviderFactory.get(type)
                }
            }
        }

        println("All providers access x ${iterations}: ${time}ms")
        assertTrue("All providers access should be fast (< 500ms for 1000 calls)", time < 500)
    }
}
