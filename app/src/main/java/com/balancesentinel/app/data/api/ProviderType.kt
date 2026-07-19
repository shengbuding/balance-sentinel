package com.balancesentinel.app.data.api

import kotlinx.serialization.Serializable

/**
 * AI供应商类型枚举
 */
@Serializable
enum class ProviderType(val id: String, val displayName: String) {
    // 海外供应商
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic"),
    GEMINI("gemini", "Gemini"),
    MISTRAL("mistral", "Mistral"),
    COHERE("cohere", "Cohere"),

    // 国内供应商
    DEEPSEEK("deepseek", "DeepSeek"),
    QWEN("qwen", "通义千问"),
    WENXIN("wenxin", "文心一言"),
    ZHIPU("zhipu", "智谱GLM"),
    MOONSHOT("moonshot", "Moonshot"),
    DOUBAO("doubao", "豆包"),
    BAICHUAN("baichuan", "百川"),

    // 自定义
    CUSTOM("custom", "自定义");

    companion object {
        fun fromId(id: String): ProviderType {
            return entries.find { it.id == id } ?: DEEPSEEK
        }
    }
}
