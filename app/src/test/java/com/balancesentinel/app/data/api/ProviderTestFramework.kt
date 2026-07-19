package com.balancesentinel.app.data.api

import org.junit.Assert.*
import org.junit.Test

/**
 * 供应商参数化测试框架
 * 用于测试所有供应商的基本功能
 */
abstract class ProviderTestFramework {

    /**
     * 子类提供供应商实例
     */
    abstract fun getProvider(): AiProvider

    /**
     * 子类提供测试配置
     */
    abstract fun getTestConfig(): ProviderConfig

    /**
     * 测试供应商类型
     */
    @Test
    fun testProviderType() {
        val provider = getProvider()
        assertNotNull("Provider should not be null", provider)
        assertNotNull("Provider type should not be null", provider.providerType)
        assertNotNull("Display name should not be null", provider.displayName)
        assertTrue("Display name should not be empty", provider.displayName.isNotBlank())
    }

    /**
     * 测试支持的特性
     */
    @Test
    fun testSupportedFeatures() {
        val provider = getProvider()
        val features = provider.supportedFeatures
        assertNotNull("Supported features should not be null", features)
        assertTrue("Should support at least MODELS", features.contains(ProviderFeature.MODELS))
    }

    /**
     * 测试配置字段
     */
    @Test
    fun testRequiredFields() {
        val provider = getProvider()
        val fields = provider.getRequiredFields()
        assertNotNull("Required fields should not be null", fields)
        assertTrue("Should have at least one field", fields.isNotEmpty())

        // 检查API Key字段
        val apiKeyField = fields.find { it.key == "apiKey" }
        assertNotNull("Should have apiKey field", apiKeyField)
        assertTrue("apiKey field should be required", apiKeyField!!.required)
    }

    /**
     * 测试API Key验证
     */
    @Test
    fun testApiKeyValidation() {
        val provider = getProvider()

        // 空Key应该无效
        assertFalse("Empty API key should be invalid", provider.validateApiKeyFormat(""))

        // 有效Key格式（根据供应商类型）
        val validKey = when (provider.providerType) {
            ProviderType.DEEPSEEK -> "sk-test1234567890"
            ProviderType.MOONSHOT -> "sk-test1234567890"
            ProviderType.OPENAI -> "sk-test1234567890"
            ProviderType.ANTHROPIC -> "sk-ant-test1234567890"
            else -> "test-api-key-1234567890"
        }
        assertTrue("Valid API key format should be accepted", provider.validateApiKeyFormat(validKey))
    }

    /**
     * 测试余额查询（需要实际API Key）
     */
    @Test
    open fun testGetBalance() {
        val provider = getProvider()
        val config = getTestConfig()

        // 如果没有配置，跳过测试
        if (config.apiKey.isBlank()) {
            println("Skipping balance test - no API key configured")
            return
        }

        // 注意：这个测试需要实际的API调用
        // 在CI/CD环境中应该被跳过
        println("Balance test would call: ${provider.providerType.displayName}")
    }

    /**
     * 测试错误处理
     */
    @Test
    fun testErrorHandling() {
        val provider = getProvider()

        // 使用无效的API Key测试错误处理
        val invalidConfig = ProviderConfig(
            providerType = provider.providerType,
            credentials = mapOf("apiKey" to "invalid-key"),
            settings = emptyMap()
        )

        // 注意：这个测试需要实际的API调用
        // 在CI/CD环境中应该被跳过
        println("Error handling test would call: ${provider.providerType.displayName}")
    }
}

/**
 * DeepSeek供应商测试
 */
class DeepSeekProviderTest : ProviderTestFramework() {
    override fun getProvider() = ProviderFactory.get(ProviderType.DEEPSEEK)

    override fun getTestConfig(): ProviderConfig {
        val creds = HashMap<String, String>()
        creds["apiKey"] = System.getenv("DEEPSEEK_API_KEY") ?: ""
        return ProviderConfig(
            providerType = ProviderType.DEEPSEEK,
            credentials = creds
        )
    }
}

/**
 * Moonshot供应商测试
 */
class MoonshotProviderTest : ProviderTestFramework() {
    override fun getProvider() = ProviderFactory.get(ProviderType.MOONSHOT)

    override fun getTestConfig(): ProviderConfig {
        val creds = HashMap<String, String>()
        creds["apiKey"] = System.getenv("MOONSHOT_API_KEY") ?: ""
        return ProviderConfig(
            providerType = ProviderType.MOONSHOT,
            credentials = creds
        )
    }
}

/**
 * 豆包供应商测试
 */
class DoubaoProviderTest : ProviderTestFramework() {
    override fun getProvider() = ProviderFactory.get(ProviderType.DOUBAO)

    override fun getTestConfig(): ProviderConfig {
        val creds = HashMap<String, String>()
        creds["apiKey"] = System.getenv("DOUBAO_API_KEY") ?: ""
        return ProviderConfig(
            providerType = ProviderType.DOUBAO,
            credentials = creds
        )
    }
}

/**
 * 百川供应商测试
 */
class BaichuanProviderTest : ProviderTestFramework() {
    override fun getProvider() = ProviderFactory.get(ProviderType.BAICHUAN)

    override fun getTestConfig(): ProviderConfig {
        val creds = HashMap<String, String>()
        creds["apiKey"] = System.getenv("BAICHUAN_API_KEY") ?: ""
        return ProviderConfig(
            providerType = ProviderType.BAICHUAN,
            credentials = creds
        )
    }
}

/**
 * 通义千问供应商测试
 */
class QwenProviderTest : ProviderTestFramework() {
    override fun getProvider() = ProviderFactory.get(ProviderType.QWEN)

    override fun getTestConfig(): ProviderConfig {
        val creds = HashMap<String, String>()
        creds["apiKey"] = System.getenv("QWEN_API_KEY") ?: ""
        return ProviderConfig(
            providerType = ProviderType.QWEN,
            credentials = creds
        )
    }
}
