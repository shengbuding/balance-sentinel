package com.balancesentinel.app.data.model

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderType
import kotlinx.serialization.Serializable
import java.net.URI

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
        val credentials = mutableMapOf(
            "apiKey" to apiKey,
            "accountId" to id,
            "accountLabel" to label
        )
        credentials.putAll(extraCredentials)
        val settings = extraSettings.toMutableMap()
        if (usageScript != null) {
            settings["usageScript"] = usageScript
        }
        settings["usageScriptEnabled"] = usageScriptEnabled.toString()
        settings["authorizedScriptOrigins"] = authorizedScriptOrigins
            .map(::canonicalOrigin)
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",")
        return ProviderConfig(
            providerType = providerType,
            credentials = credentials,
            settings = settings
        )
    }

    private fun canonicalOrigin(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val uri = URI(trimmed)
            if (uri.scheme == null || uri.host == null) trimmed.removeSuffix("/")
            else buildString {
                append(uri.scheme.lowercase())
                append("://")
                append(uri.host.lowercase())
                if (uri.port != -1 &&
                    !((uri.scheme.equals("https", true) && uri.port == 443) ||
                        (uri.scheme.equals("http", true) && uri.port == 80))
                ) {
                    append(":${uri.port}")
                }
            }
        }.getOrElse { trimmed.removeSuffix("/") }
    }
}

data class AccountDraft(
    val label: String,
    val apiKey: String,
    val providerType: ProviderType,
    val extraCredentials: Map<String, String>,
    val extraSettings: Map<String, String>,
    val usageScript: String?,
    val usageScriptEnabled: Boolean,
    val authorizedScriptOrigins: Set<String>
)
