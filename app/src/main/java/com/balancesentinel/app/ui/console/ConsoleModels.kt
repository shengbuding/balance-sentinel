package com.balancesentinel.app.ui.console

import com.balancesentinel.app.data.api.balance.WebOrigin
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 控制台平台配置
 */
@Serializable
data class ConsolePlatform(
    val id: String,
    val name: String,
    val loginUrl: String,
    val dashboardUrl: String,
    val successUrlPatterns: List<String>,
    val loginPagePatterns: List<String> = listOf("/sign_in", "/login", "/register", "/oauth"),
    val isPreset: Boolean = false,
    val description: String? = null
) {
    /**
     * 判断 URL 是否表示登录成功
     */
    fun isLoginSuccess(url: String): Boolean {
        val current = url.toHttpUrlOrNull() ?: return false
        val dashboard = dashboardUrl.toHttpUrlOrNull() ?: return false
        if (WebOrigin.from(current) != WebOrigin.from(dashboard)) return false
        val location = current.pathAndFragment()

        if (loginPagePatterns.any { location.matchesLocationPattern(it) }) return false
        if (successUrlPatterns.any { location.matchesLocationPattern(it) }) return true

        val dashboardPatterns = listOf("/dashboard", "/console", "/overview", "/home", "/serverless", "/models")
        return dashboardPatterns.any { location.matchesLocationPattern(it) }
    }

    fun isLoginPage(url: String): Boolean {
        val current = url.toHttpUrlOrNull() ?: return false
        val configuredOrigins = listOf(loginUrl, dashboardUrl)
            .mapNotNull { it.toHttpUrlOrNull() }
            .map(WebOrigin::from)
            .toSet()
        if (WebOrigin.from(current) !in configuredOrigins) return false
        val location = current.pathAndFragment()
        val commonLoginPatterns = listOf("/sign_in", "/login", "/register", "/oauth", "/signin", "/auth")
        return (loginPagePatterns + commonLoginPatterns).any { location.matchesLocationPattern(it) }
    }

    private fun HttpUrl.pathAndFragment(): String = buildString {
        append(encodedPath)
        encodedFragment?.let {
            append('#')
            append(it)
        }
    }

    private fun String.matchesLocationPattern(pattern: String): Boolean {
        val normalizedPattern = pattern.toHttpUrlOrNull()?.pathAndFragment()
            ?: pattern.substring(pattern.indexOf('/').takeIf { it >= 0 } ?: 0)
        return normalizedPattern.isNotEmpty() && contains(normalizedPattern, ignoreCase = true)
    }
}

/**
 * 预定义平台
 */
object PresetPlatforms {
    val DEEPSEEK = ConsolePlatform(
        id = "deepseek",
        name = "DeepSeek",
        loginUrl = "https://platform.deepseek.com/sign_in",
        dashboardUrl = "https://platform.deepseek.com/overview",
        successUrlPatterns = listOf(
            "platform.deepseek.com/overview",
            "platform.deepseek.com/dashboard",
            "platform.deepseek.com/usage",
            "platform.deepseek.com/billing"
        ),
        isPreset = true,
        description = "查看 DeepSeek 账户余额、用量统计、模型详情"
    )

    val MIMO = ConsolePlatform(
        id = "mimo",
        name = "Xiaomi MiMo",
        loginUrl = "https://platform.xiaomimimo.com/sign_in",
        dashboardUrl = "https://platform.xiaomimimo.com/#/console/usage",
        successUrlPatterns = listOf(
            "platform.xiaomimimo.com/#/console",
            "platform.xiaomimimo.com/console",
            "platform.xiaomimimo.com/#/overview"
        ),
        isPreset = true,
        description = "查看 MiMo 账户余额、用量统计、Token Plan"
    )

    val MODEL_ARK = ConsolePlatform(
        id = "model_ark",
        name = "模力方舟",
        loginUrl = "https://ai.gitee.com/login",
        dashboardUrl = "https://ai.gitee.com/serverless-api",
        successUrlPatterns = listOf(
            "ai.gitee.com/serverless-api",
            "ai.gitee.com/dashboard",
            "ai.gitee.com/console",
            "ai.gitee.com/zvgktinv",
            "ai.gitee.com/models"
        ),
        isPreset = true,
        description = "查看模力方舟账户余额、用量统计"
    )

    /** 所有预设平台 */
    val ALL = listOf(DEEPSEEK, MIMO, MODEL_ARK)

    /**
     * 根据ID获取平台
     */
    fun getById(id: String): ConsolePlatform? {
        return ALL.find { it.id == id }
    }
}
