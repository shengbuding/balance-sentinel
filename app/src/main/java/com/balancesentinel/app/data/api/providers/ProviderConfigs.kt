package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ConfigField
import com.balancesentinel.app.data.api.FieldType
import com.balancesentinel.app.data.api.ProviderType

/**
 * 供应商配置定义
 */
object ProviderConfigs {

    /**
     * 获取供应商的API Key格式提示
     */
    fun getApiKeyHint(type: ProviderType): String {
        return when (type) {
            ProviderType.DEEPSEEK -> "sk-开头的API Key"
            ProviderType.MOONSHOT -> "sk-开头的API Key"
            ProviderType.DOUBAO -> "火山引擎API Key"
            ProviderType.BAICHUAN -> "百川API Key"
            ProviderType.QWEN -> "DashScope API Key"
            ProviderType.ZHIPU -> "智谱API Key（需生成JWT）"
            ProviderType.WENXIN -> "百度API Key"
            ProviderType.OPENAI -> "sk-开头的API Key"
            ProviderType.ANTHROPIC -> "sk-ant-开头的API Key"
            ProviderType.GEMINI -> "Google AI API Key"
            ProviderType.MISTRAL -> "Mistral API Key"
            ProviderType.COHERE -> "Cohere API Key"
            ProviderType.CUSTOM -> "自定义API Key"
        }
    }

    /**
     * 获取供应商的API Key验证规则
     */
    fun validateApiKey(type: ProviderType, apiKey: String): Boolean {
        return when (type) {
            ProviderType.DEEPSEEK -> apiKey.startsWith("sk-") && apiKey.length > 10
            ProviderType.MOONSHOT -> apiKey.startsWith("sk-") && apiKey.length > 10
            ProviderType.DOUBAO -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.BAICHUAN -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.QWEN -> apiKey.startsWith("sk-") && apiKey.length > 10
            ProviderType.ZHIPU -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.WENXIN -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.OPENAI -> apiKey.startsWith("sk-") && apiKey.length > 10
            ProviderType.ANTHROPIC -> apiKey.startsWith("sk-ant-") && apiKey.length > 10
            ProviderType.GEMINI -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.MISTRAL -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.COHERE -> apiKey.isNotBlank() && apiKey.length > 10
            ProviderType.CUSTOM -> apiKey.isNotBlank()
        }
    }

    /**
     * 获取供应商的配置字段
     */
    fun getConfigFields(type: ProviderType): List<ConfigField> {
        val fields = mutableListOf(
            ConfigField(
                key = "apiKey",
                displayName = "API Key",
                type = FieldType.PASSWORD,
                required = true,
                hint = getApiKeyHint(type)
            )
        )

        // 特定供应商的额外字段
        when (type) {
            ProviderType.ZHIPU -> {
                fields.add(
                    ConfigField(
                        key = "secretKey",
                        displayName = "Secret Key",
                        type = FieldType.PASSWORD,
                        required = true,
                        hint = "用于生成JWT Token"
                    )
                )
            }
            ProviderType.WENXIN -> {
                fields.add(
                    ConfigField(
                        key = "secretKey",
                        displayName = "Secret Key",
                        type = FieldType.PASSWORD,
                        required = true,
                        hint = "百度云Secret Key"
                    )
                )
            }
            ProviderType.CUSTOM -> {
                fields.add(
                    ConfigField(
                        key = "baseUrl",
                        displayName = "API Base URL",
                        type = FieldType.URL,
                        required = true,
                        hint = "https://api.example.com/v1"
                    )
                )
            }
            else -> {}
        }

        return fields
    }

    /**
     * 获取供应商默认初始余额
     */
    fun getDefaultInitialBalance(type: ProviderType): Double {
        return when (type) {
            ProviderType.DEEPSEEK -> 0.0  // 使用真实API
            ProviderType.MOONSHOT -> 100.0
            ProviderType.DOUBAO -> 100.0
            ProviderType.BAICHUAN -> 100.0
            ProviderType.QWEN -> 100.0
            ProviderType.ZHIPU -> 100.0
            ProviderType.WENXIN -> 100.0
            ProviderType.OPENAI -> 100.0
            ProviderType.ANTHROPIC -> 100.0
            ProviderType.GEMINI -> 100.0
            ProviderType.MISTRAL -> 100.0
            ProviderType.COHERE -> 100.0
            ProviderType.CUSTOM -> 100.0
        }
    }
}
