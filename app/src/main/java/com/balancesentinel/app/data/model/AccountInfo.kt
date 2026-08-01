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
 * @param usageScript 自定义余额查询脚本（仅自定义供应商有效）
 */
@Serializable
data class AccountInfo(
    val id: String,
    val label: String,
    val apiKey: String,
    val providerType: ProviderType = ProviderType.DEEPSEEK,
    val extraCredentials: Map<String, String> = emptyMap(),
    val extraSettings: Map<String, String> = emptyMap(),
    val usageScript: String? = null,
    val usageScriptEnabled: Boolean = true,
    val authorizedScriptOrigins: Set<String> = emptySet(),
    val revision: Long = 0
) {
    /**
     * 转换为ProviderConfig
     */
    fun toConfig(): ProviderConfig {
        val credentials = extraCredentials.toMutableMap()
        credentials["apiKey"] = apiKey
        credentials["accountId"] = id
        credentials["accountLabel"] = label
        val settings = extraSettings.toMutableMap()
        settings.remove("usageScript")
        settings.remove("usageScriptEnabled")
        settings.remove("authorizedScriptOrigins")
        // 添加自定义脚本到settings中
        if (usageScript != null) {
            settings["usageScript"] = usageScript
        }
        settings["usageScriptEnabled"] = usageScriptEnabled.toString()
        settings["authorizedScriptOrigins"] = authorizedScriptOrigins
            .map { origin ->
                if (origin.startsWith("http://") || origin.startsWith("https://")) {
                    origin.removeSuffix("/")
                } else {
                    origin
                }
            }
            .sorted()
            .joinToString(",")
        return ProviderConfig(
            providerType = providerType,
            credentials = credentials,
            settings = settings
        )
    }
}

data class AccountDraft(
    val label: String,
    val apiKey: String,
    val providerType: ProviderType,
    val extraCredentials: Map<String, String> = emptyMap(),
    val extraSettings: Map<String, String> = emptyMap(),
    val usageScript: String? = null,
    val usageScriptEnabled: Boolean = true,
    val authorizedScriptOrigins: Set<String> = emptySet()
)

sealed interface AccountSaveResult {
    val account: AccountInfo

    data class Created(override val account: AccountInfo) : AccountSaveResult
    data class Updated(val before: AccountInfo, override val account: AccountInfo) : AccountSaveResult
    data class Replaced(val before: AccountInfo, override val account: AccountInfo) : AccountSaveResult
}
