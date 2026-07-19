package com.balancesentinel.app.data.model

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderType
import kotlinx.serialization.Serializable

/**
 * 账户信息
 * @param id 账户ID（基于API Key的SHA-256哈希）
 * @param label 用户自定义标签
 * @param apiKey API Key
 * @param providerType 供应商类型（默认DeepSeek，兼容旧数据）
 * @param extraCredentials 额外凭证（如secretKey、orgId等）
 * @param extraSettings 额外设置（如baseUrl覆盖等）
 */
@Serializable
data class AccountInfo(
    val id: String,
    val label: String,
    val apiKey: String,
    val providerType: ProviderType = ProviderType.DEEPSEEK,
    val extraCredentials: Map<String, String> = emptyMap(),
    val extraSettings: Map<String, String> = emptyMap()
) {
    /**
     * 转换为ProviderConfig
     */
    fun toConfig(): ProviderConfig {
        val credentials = mutableMapOf("apiKey" to apiKey)
        credentials.putAll(extraCredentials)
        return ProviderConfig(
            providerType = providerType,
            credentials = credentials,
            settings = extraSettings
        )
    }
}
