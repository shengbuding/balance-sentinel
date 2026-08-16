package com.balancesentinel.app.data.api.providers

import androidx.annotation.StringRes
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ConfigField
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.FieldType
import com.balancesentinel.app.data.api.ProviderType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 供应商配置定义
 */
object ProviderConfigs {

    /**
     * 获取供应商的API Key格式提示
     */
    @StringRes
    fun getApiKeyHint(type: ProviderType): Int {
        return when (type) {
            ProviderType.DEEPSEEK,
            ProviderType.MOONSHOT,
            ProviderType.OPENAI -> R.string.account_hint_api_key_sk_prefix
            ProviderType.DOUBAO -> R.string.account_hint_api_key_volcengine
            ProviderType.BAICHUAN -> R.string.account_hint_api_key_baichuan
            ProviderType.QWEN -> R.string.account_hint_api_key_dashscope
            ProviderType.ZHIPU -> R.string.account_hint_api_key_zhipu
            ProviderType.WENXIN -> R.string.account_hint_api_key_baidu
            ProviderType.ANTHROPIC -> R.string.account_hint_api_key_anthropic
            ProviderType.GEMINI -> R.string.account_hint_api_key_google
            ProviderType.MISTRAL -> R.string.account_hint_api_key_mistral
            ProviderType.COHERE -> R.string.account_hint_api_key_cohere
            ProviderType.MODEL_ARK -> R.string.account_hint_api_key_model_ark
            ProviderType.CUSTOM -> R.string.account_hint_api_key_custom
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
            ProviderType.MODEL_ARK -> apiKey.isNotBlank() && apiKey.length > 10
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
                displayNameRes = R.string.add_account_key_label,
                type = FieldType.PASSWORD,
                required = true,
                storage = ConfigFieldStorage.PRIMARY_CREDENTIAL,
                hintRes = getApiKeyHint(type)
            )
        )

        // 特定供应商的额外字段
        when (type) {
            ProviderType.ZHIPU -> {
                fields.add(
                    ConfigField(
                        key = "secretKey",
                        displayNameRes = R.string.account_field_secret_key,
                        type = FieldType.PASSWORD,
                        required = true,
                        storage = ConfigFieldStorage.EXTRA_CREDENTIAL,
                        hintRes = R.string.account_hint_secret_key_jwt
                    )
                )
            }
            ProviderType.WENXIN -> {
                fields.add(
                    ConfigField(
                        key = "secretKey",
                        displayNameRes = R.string.account_field_secret_key,
                        type = FieldType.PASSWORD,
                        required = true,
                        storage = ConfigFieldStorage.EXTRA_CREDENTIAL,
                        hintRes = R.string.account_hint_secret_key_baidu
                    )
                )
            }
            ProviderType.CUSTOM -> {
                fields.add(
                    ConfigField(
                        key = "baseUrl",
                        displayNameRes = R.string.account_field_base_url,
                        type = FieldType.URL,
                        required = true,
                        storage = ConfigFieldStorage.SETTING,
                        hintRes = R.string.account_hint_base_url
                    )
                )
                fields.add(
                    ConfigField(
                        key = "accessToken",
                        displayNameRes = R.string.account_field_access_token,
                        type = FieldType.PASSWORD,
                        required = false,
                        storage = ConfigFieldStorage.EXTRA_CREDENTIAL,
                        hintRes = R.string.account_hint_access_token
                    )
                )
                fields.add(
                    ConfigField(
                        key = "userId",
                        displayNameRes = R.string.account_field_user_id,
                        type = FieldType.TEXT,
                        required = false,
                        storage = ConfigFieldStorage.EXTRA_CREDENTIAL,
                        hintRes = R.string.account_hint_user_id
                    )
                )
            }
            else -> {}
        }

        return fields
    }

    fun validateFieldValue(field: ConfigField, value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isEmpty()) return !field.required

        return when (field.type) {
            FieldType.URL -> normalized.toHttpUrlOrNull()?.scheme in setOf("http", "https")
            else -> true
        }
    }

    fun validateFieldValues(type: ProviderType, values: Map<String, String>): Boolean =
        getConfigFields(type).all { field ->
            validateFieldValue(field, values[field.key].orEmpty())
        }

    fun valuesForStorage(
        type: ProviderType,
        values: Map<String, String>,
        storage: ConfigFieldStorage
    ): Map<String, String> = getConfigFields(type)
        .asSequence()
        .filter { it.storage == storage }
        .mapNotNull { field -> values[field.key]?.let { field.key to it } }
        .toMap()

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
            ProviderType.MODEL_ARK -> 0.0  // 使用真实API
            ProviderType.CUSTOM -> 100.0
        }
    }
}
