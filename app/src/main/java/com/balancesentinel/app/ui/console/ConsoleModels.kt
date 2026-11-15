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

    /**
     * 当前地址是否足以证明「登录已失效」
     *
     * 有的站点登录页就在首页：入口地址本身就是登录页，单看 URL 无法区分
     * 「没登录看到登录页」和「已登录看到首页」。这类平台一律不自动提示，
     * 否则每次进控制台都会误报「登录已失效」。
     */
    fun reportsLoginExpiryAt(url: String): Boolean {
        if (isLoginPage(dashboardUrl)) return false
        return isLoginPage(url)
    }

    /**
     * 该地址是否可以记为「下次直接打开的仪表盘地址」
     *
     * 只接受 https、与仪表盘同源、且不是登录页的地址，
     * 避免把跳去外站的地址或登录页写进会话。
     */
    fun isRememberableDashboardUrl(url: String?): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        val dashboard = dashboardUrl.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        if (WebOrigin.from(parsed) != WebOrigin.from(dashboard)) return false
        return !isLoginPage(url)
    }

    /**
     * 打开控制台时应该加载的地址
     *
     * 优先回到上次真正停留过的仪表盘页面。平台只填了网站（首页）时，
     * 退而用配置里最具体的成功路径，避免每次都从首页重新点进去。
     */
    fun preferredDashboardUrl(lastVisitedUrl: String? = null): String {
        if (lastVisitedUrl != null && isRememberableDashboardUrl(lastVisitedUrl)) {
            return lastVisitedUrl
        }
        if (isHomeDashboard()) {
            for (pattern in successUrlPatterns) {
                val location = normalizeLocationPattern(pattern) ?: continue
                if (location == "/") continue
                return dashboardUrlFor(location) ?: continue
            }
        }
        return dashboardUrl
    }

    private fun isHomeDashboard(): Boolean {
        val dashboard = dashboardUrl.toHttpUrlOrNull() ?: return false
        return dashboard.encodedPath == "/" && dashboard.encodedFragment.isNullOrEmpty()
    }

    private fun dashboardUrlFor(location: String): String? {
        val dashboard = dashboardUrl.toHttpUrlOrNull() ?: return null
        val hashIndex = location.indexOf('#')
        val path = if (hashIndex >= 0) location.substring(0, hashIndex) else location
        val fragment = if (hashIndex >= 0) location.substring(hashIndex + 1) else null
        return try {
            dashboard.newBuilder()
                .encodedPath(path.ifEmpty { "/" })
                .encodedFragment(fragment)
                .build()
                .toString()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun HttpUrl.pathAndFragment(): String = buildString {
        append(encodedPath)
        encodedFragment?.let {
            append('#')
            append(it)
        }
    }

    /**
     * 把匹配模式归一化成「路径 + #hash 路由」
     *
     * 模式可以是完整 URL，也可以是 "/overview"、"/#/console/usage" 这样的片段；
     * 空模式返回 null。
     */
    private fun normalizeLocationPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        val location = trimmed.toHttpUrlOrNull()?.pathAndFragment()
            ?: trimmed.substring(trimmed.indexOf('/').takeIf { it >= 0 } ?: 0)
        if (location.isEmpty()) return null
        val withSlash = if (location.startsWith("/")) location else "/$location"
        return withSlash.trimEnd('/').ifEmpty { "/" }
    }

    /**
     * 判断当前地址（路径 + #hash 路由）是否命中匹配模式
     *
     * 只按路径段边界匹配，而不是任意子串：
     * - "/overview" 命中 "/overview"、"/overview/usage"，不命中 "/overview-archive"；
     * - "/" 只命中站点根，不能当成「任意地址」的通配符 —— 否则登录页在首页的站点
     *   会把每个页面都判成登录页，一进控制台就误报「登录已失效」。
     */
    private fun String.matchesLocationPattern(pattern: String): Boolean {
        val normalized = normalizeLocationPattern(pattern) ?: return false
        if (normalized == "/") return this == "/"
        var index = indexOf(normalized, ignoreCase = true)
        while (index >= 0) {
            val end = index + normalized.length
            if (end == length || this[end] == '/' || this[end] == '#' || this[end] == '?') return true
            index = indexOf(normalized, index + 1, ignoreCase = true)
        }
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
