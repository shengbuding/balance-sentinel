package com.balancesentinel.app.data.api

import com.balancesentinel.app.data.api.balance.BalanceQueryService
import com.balancesentinel.app.data.api.providers.DeepSeekProvider
import com.balancesentinel.app.data.api.providers.OpenAiCompatibleProvider
import java.util.concurrent.ConcurrentHashMap

object ProviderFactory {
    private val providers = ConcurrentHashMap<String, AiProvider>()
    private val balanceQueryService = BalanceQueryService()

    fun get(type: ProviderType, baseUrl: String? = null): AiProvider = when (type) {
        ProviderType.CUSTOM -> {
            require(!baseUrl.isNullOrBlank()) { "自定义供应商需要指定baseUrl" }
            providers.getOrPut("custom_$baseUrl") {
                OpenAiCompatibleProvider(type, baseUrl, balanceQueryService)
            }
        }
        else -> providers.getOrPut(type.name) { create(type) }
    }

    private fun create(type: ProviderType): AiProvider = when (type) {
        ProviderType.DEEPSEEK -> DeepSeekProvider(balanceQueryService)
        ProviderType.MOONSHOT -> openAiCompatible(type, "https://api.moonshot.cn")
        ProviderType.DOUBAO -> openAiCompatible(type, "https://ark.cn-beijing.volces.com/api/v3")
        ProviderType.BAICHUAN -> openAiCompatible(type, "https://api.baichuan-ai.com")
        ProviderType.QWEN -> openAiCompatible(type, "https://dashscope.aliyuncs.com/compatible-mode/v1")
        ProviderType.ZHIPU -> openAiCompatible(type, "https://open.bigmodel.cn/api/paas/v4")
        ProviderType.WENXIN -> openAiCompatible(type, "https://aip.baidubce.com")
        ProviderType.OPENAI -> openAiCompatible(type, "https://api.openai.com/v1")
        ProviderType.ANTHROPIC -> openAiCompatible(type, "https://api.anthropic.com")
        ProviderType.GEMINI -> openAiCompatible(type, "https://generativelanguage.googleapis.com/v1beta")
        ProviderType.MISTRAL -> openAiCompatible(type, "https://api.mistral.ai/v1")
        ProviderType.COHERE -> openAiCompatible(type, "https://api.cohere.ai/v1")
        ProviderType.MODEL_ARK -> openAiCompatible(type, "https://ai.gitee.com")
        ProviderType.CUSTOM -> error("自定义供应商需要指定baseUrl")
    }

    private fun openAiCompatible(type: ProviderType, baseUrl: String): AiProvider =
        OpenAiCompatibleProvider(type, baseUrl, balanceQueryService)

    fun register(type: ProviderType, provider: AiProvider, baseUrl: String? = null) {
        val key = if (type == ProviderType.CUSTOM && baseUrl != null) {
            "custom_$baseUrl"
        } else {
            type.name
        }
        providers[key] = provider
    }
}
