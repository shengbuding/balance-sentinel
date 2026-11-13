package com.balancesentinel.app.ui.console

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** 一个被用户（手动或自动）标记的页面。 */
data class MarkedPage(
    val kind: ConsolePageKind,
    val url: String,
    val pattern: String,
    val markedAt: Long
)

enum class ConsolePageKind { LOGIN, DASHBOARD }

/** 一次「边浏览边标记」的产物：回填到表单的内容。 */
data class ConsoleCaptureResult(
    val loginUrl: String? = null,
    val dashboardUrl: String? = null,
    val successPatterns: List<String> = emptyList(),
    val loginPatterns: List<String> = emptyList()
)

/** 表单校验错误。 */
enum class ConsoleDraftError {
    NameMissing,
    WebsiteInvalid,
    UrlsMissing,
    LoginUrlInvalid,
    DashboardUrlInvalid
}

/**
 * 控制台配置草稿：既用于「新增自定义控制台」，也用于「修改已添加的控制台」。
 *
 * 纯 Kotlin 逻辑（不依赖 Android 框架），所以表单校验、URL 归一化、页面模式推导
 * 都能在 JVM 单元测试里直接验证。
 *
 * 关键约定：用户只需要一个网站地址就能开出来一个控制台 —— 登录页和余额页都可以
 * 在「浏览并标记」里点出来，没标的就按网站地址兜底。
 */
data class ConsolePlatformDraft(
    val name: String = "",
    val website: String = "",
    val loginUrl: String = "",
    val dashboardUrl: String = "",
    val successPatterns: String = "",
    val loginPatterns: String = "",
    val description: String = ""
) {
    private val normalizedWebsite: String? get() = normalizeHttpsUrl(website)
    private val normalizedLogin: String? get() = normalizeHttpsUrl(loginUrl)
    private val normalizedDashboard: String? get() = normalizeHttpsUrl(dashboardUrl)

    /** 启动地址：登录页 → 网站地址 → 余额页。 */
    val browseUrl: String?
        get() = normalizedLogin ?: normalizedWebsite ?: normalizedDashboard

    /** 登录页最终取值（没填登录页就用网站地址）。 */
    val effectiveLoginUrl: String? get() = normalizedLogin ?: normalizedWebsite

    /** 余额页最终取值（没填余额页就用登录页）。 */
    val effectiveDashboardUrl: String?
        get() = normalizedDashboard ?: effectiveLoginUrl

    fun errors(): Set<ConsoleDraftError> = buildSet {
        if (name.isBlank()) add(ConsoleDraftError.NameMissing)
        if (website.isNotBlank() && normalizedWebsite == null) add(ConsoleDraftError.WebsiteInvalid)
        if (loginUrl.isNotBlank() && normalizedLogin == null) add(ConsoleDraftError.LoginUrlInvalid)
        if (dashboardUrl.isNotBlank() && normalizedDashboard == null) add(ConsoleDraftError.DashboardUrlInvalid)
        if (browseUrl == null) add(ConsoleDraftError.UrlsMissing)
    }

    val isValid: Boolean get() = errors().isEmpty()

    /**
     * 把一次「边浏览边标记」的结果合并进草稿。
     * 只覆盖真正标记过的字段；用户手填的内容不会被清空。
     */
    fun applyCapture(result: ConsoleCaptureResult): ConsolePlatformDraft {
        val nextLogin = result.loginUrl ?: normalizedLogin ?: normalizedWebsite
        val nextDashboard = result.dashboardUrl ?: normalizedDashboard ?: nextLogin
        return copy(
            website = website.ifBlank { nextDashboard?.let(::hostAndPath).orEmpty() },
            loginUrl = nextLogin.orEmpty(),
            dashboardUrl = nextDashboard.orEmpty(),
            successPatterns = joinPatterns(result.successPatterns).ifBlank { successPatterns },
            loginPatterns = joinPatterns(result.loginPatterns).ifBlank { loginPatterns }
        )
    }

    /** 组装成可保存的平台配置；调用前必须先确认 [isValid]。 */
    fun toPlatform(platformId: String, isPreset: Boolean): ConsolePlatform {
        val login = requireNotNull(effectiveLoginUrl) { "loginUrl" }
        val dashboard = requireNotNull(effectiveDashboardUrl) { "dashboardUrl" }
        val patterns = splitPatterns(successPatterns)
        return ConsolePlatform(
            id = platformId,
            name = name.trim(),
            loginUrl = login,
            dashboardUrl = dashboard,
            successUrlPatterns = patterns.ifEmpty { listOf(derivePattern(dashboard.toHttpUrlOrNull()!!)) },
            loginPagePatterns = splitPatterns(loginPatterns).ifEmpty { DEFAULT_LOGIN_PATTERNS },
            isPreset = isPreset,
            description = description.trim().ifBlank { null }
        )
    }

    companion object {
        val DEFAULT_LOGIN_PATTERNS = listOf("/sign_in", "/login", "/signup", "/register", "/oauth")

        fun fromPlatform(platform: ConsolePlatform): ConsolePlatformDraft = ConsolePlatformDraft(
            name = platform.name,
            website = hostAndPath(platform.dashboardUrl),
            loginUrl = platform.loginUrl,
            dashboardUrl = platform.dashboardUrl,
            successPatterns = joinPatterns(platform.successUrlPatterns),
            loginPatterns = joinPatterns(platform.loginPagePatterns),
            description = platform.description.orEmpty()
        )

        fun splitPatterns(value: String): List<String> = value
            .split(',', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

        fun joinPatterns(patterns: List<String>): String =
            patterns.map(String::trim).filter(String::isNotEmpty).distinct().joinToString(",")

        /**
         * 把用户输入的地址归一化成 HTTPS URL。
         * 只给域名（例如 `ai.gitee.com`）时自动补 `https://`；`http://` 一律拒绝。
         */
        fun normalizeHttpsUrl(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val parsed = candidate.toHttpUrlOrNull() ?: return null
            if (parsed.scheme.lowercase(Locale.US) != "https") return null
            if (parsed.host.isBlank()) return null
            return parsed.toString()
        }

        /** 域名 + 路径，用于回填「网站」输入框。 */
        fun hostAndPath(value: String): String {
            val parsed = value.trim().toHttpUrlOrNull() ?: return value.trim()
            return parsed.host + parsed.encodedPath
        }

        /**
         * 从页面 URL 推导 URL 模式。
         *
         * 现有匹配逻辑只拿「路径（+fragment）」做包含比较，所以这里也只保留路径部分：
         * `https://ai.gitee.com/#/console/usage` → `/#/console/usage`。
         * 路径过长时截断到三段，避免把一次点击里的动态 id 记成模式。
         */
        fun derivePattern(url: HttpUrl): String {
            val fragment = url.encodedFragment?.substringBefore('?').orEmpty()
            val location = if (url.encodedPath == "/" && fragment.startsWith("/")) {
                // 哈希路由：https://host/#/console/usage → /#/console/usage
                "/#" + fragment
            } else {
                url.encodedPath
            }
            return truncatePattern(location)
        }

        private fun truncatePattern(location: String): String {
            val segments = location.split('/').filter(String::isNotEmpty)
            if (segments.size <= PATTERN_SEGMENT_LIMIT) {
                return location.trimEnd('/').ifEmpty { "/" }
            }
            val prefix = if (location.startsWith("/#/")) "/#/" else "/"
            return prefix + segments.take(PATTERN_SEGMENT_LIMIT).joinToString("/")
        }

        private const val PATTERN_SEGMENT_LIMIT = 3
    }
}
