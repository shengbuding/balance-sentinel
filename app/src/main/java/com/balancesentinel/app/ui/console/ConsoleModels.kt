package com.balancesentinel.app.ui.console

import kotlinx.serialization.Serializable

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
        // 如果 URL 包含登录页面模式，返回 false
        if (loginPagePatterns.any { url.contains(it, ignoreCase = true) }) return false

        // 如果 URL 包含 successUrlPatterns 中的任何模式，返回 true
        if (successUrlPatterns.any { url.contains(it, ignoreCase = true) }) return true

        // 通用检测：如果不包含登录页面模式，且包含常见仪表盘模式，认为登录成功
        val dashboardPatterns = listOf("/dashboard", "/console", "/overview", "/home", "/serverless", "/models")
        if (dashboardPatterns.any { url.contains(it, ignoreCase = true) }) return true

        return false
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
