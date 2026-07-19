package com.balancesentinel.app.data.api

import com.balancesentinel.app.data.api.providers.DeepSeekProvider
import com.balancesentinel.app.data.api.providers.OpenAiCompatibleProvider

/**
 * 供应商工厂
 * 根据ProviderType创建对应的AiProvider实例
 */
object ProviderFactory {
    private val providers = mutableMapOf<ProviderType, AiProvider>()

    /**
     * 获取供应商实例
     * @param type 供应商类型
     * @return AiProvider实例
     * @throws IllegalArgumentException 未知的供应商类型
     */
    fun get(type: ProviderType): AiProvider {
        return providers.getOrPut(type) { create(type) }
    }

    /**
     * 创建供应商实例
     */
    private fun create(type: ProviderType): AiProvider {
        return when (type) {
            ProviderType.DEEPSEEK -> DeepSeekProvider()
            ProviderType.MOONSHOT -> OpenAiCompatibleProvider(type, "https://api.moonshot.cn")
            ProviderType.DOUBAO -> OpenAiCompatibleProvider(type, "https://ark.cn-beijing.volces.com/api/v3")
            ProviderType.BAICHUAN -> OpenAiCompatibleProvider(type, "https://api.baichuan-ai.com")
            ProviderType.QWEN -> OpenAiCompatibleProvider(type, "https://dashscope.aliyuncs.com/compatible-mode/v1")
            ProviderType.ZHIPU -> OpenAiCompatibleProvider(type, "https://open.bigmodel.cn/api/paas/v4")
            ProviderType.WENXIN -> OpenAiCompatibleProvider(type, "https://aip.baidubce.com")
            ProviderType.OPENAI -> OpenAiCompatibleProvider(type, "https://api.openai.com/v1")
            ProviderType.ANTHROPIC -> OpenAiCompatibleProvider(type, "https://api.anthropic.com")
            ProviderType.GEMINI -> OpenAiCompatibleProvider(type, "https://generativelanguage.googleapis.com/v1beta")
            ProviderType.MISTRAL -> OpenAiCompatibleProvider(type, "https://api.mistral.ai/v1")
            ProviderType.COHERE -> OpenAiCompatibleProvider(type, "https://api.cohere.ai/v1")
            ProviderType.CUSTOM -> throw IllegalArgumentException("自定义供应商需要指定baseUrl")
        }
    }

    /**
     * 注册自定义供应商
     */
    fun register(type: ProviderType, provider: AiProvider) {
        providers[type] = provider
    }
}
