package com.balancesentinel.app.data.console

import com.balancesentinel.app.ui.console.ConsoleCaptureResult
import com.balancesentinel.app.ui.console.ConsolePageKind
import com.balancesentinel.app.ui.console.ConsolePlatformDraft
import com.balancesentinel.app.ui.console.MarkedPage
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * 「一边浏览一边标记」的会话状态。
 *
 * 纯 Kotlin，不碰 Android 框架：URL 归一化、模式推导、自动标记上限都在这里，
 * 可以直接在 JVM 单元测试里验证。
 */
class ConsolePageCaptureSession(
    private val maxAutoMarks: Int = MAX_AUTO_MARKS,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val marked = mutableListOf<MarkedPage>()
    private var autoMarks = 0

    val markedPages: List<MarkedPage> get() = marked.toList()
    val autoMarkCount: Int get() = autoMarks
    val autoMarkLimitReached: Boolean get() = autoMarks >= maxAutoMarks

    /**
     * 标记一个页面。
     *
     * @param auto 自动标记（页面自己判定像仪表盘）；自动标记有数量上限，
     *             手动标记永远允许，用户点了就该记下来。
     * @return 是否真的新增了一条标记（重复标记返回 false，UI 不用刷新）
     */
    fun mark(kind: ConsolePageKind, url: String, auto: Boolean = false): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme.lowercase(Locale.US) != "https") return false
        if (auto && autoMarkLimitReached) return false

        val pattern = ConsolePlatformDraft.derivePattern(parsed)
        if (pattern.isEmpty()) return false
        if (marked.any { it.kind == kind && it.pattern == pattern }) return false

        marked += MarkedPage(
            kind = kind,
            url = parsed.toString(),
            pattern = pattern,
            markedAt = now()
        )
        if (auto) autoMarks++
        return true
    }

    /** 撤销一条标记（按模式匹配）。 */
    fun unmark(page: MarkedPage): Boolean = marked.removeAll { it.kind == page.kind && it.pattern == page.pattern }

    fun clear() {
        marked.clear()
        autoMarks = 0
    }

    /** 把当前标记结果整理成表单可以直接合并的形式。 */
    fun result(): ConsoleCaptureResult {
        val loginPages = marked.filter { it.kind == ConsolePageKind.LOGIN }
        val dashboardPages = marked.filter { it.kind == ConsolePageKind.DASHBOARD }
        return ConsoleCaptureResult(
            loginUrl = loginPages.lastOrNull()?.url,
            dashboardUrl = dashboardPages.lastOrNull()?.url,
            successPatterns = dashboardPages.map(MarkedPage::pattern).distinct(),
            loginPatterns = loginPages.map(MarkedPage::pattern).distinct()
        )
    }

    companion object {
        const val MAX_AUTO_MARKS = 6

        /**
         * 「同一个站点」判断：只允许在起始域名及其子域/父域之间跳转，
         * 其他域名一律交给系统浏览器，避免把内嵌浏览器变成任意网页浏览器。
         */
        fun isSameSite(startUrl: String, candidateUrl: String): Boolean {
            val startHost = startUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
            val candidateHost = candidateUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
            if (startHost == candidateHost) return true
            return candidateHost.endsWith(".$startHost") || startHost.endsWith(".$candidateHost")
        }
    }
}

/**
 * 自动标记的判断规则：什么样的页面看起来像「余额/用量」页。
 *
 * 只看 main frame 的 URL 和页面可见文本，不做 DOM 结构分析；
 * 宁可漏标让用户手动点，也不要乱标。
 */
object ConsoleAutoMarkHeuristics {
    private val LOGIN_PATH_HINTS = listOf("/login", "/sign_in", "/signin", "/signup", "/register", "/oauth", "/auth")
    private val LOGIN_TEXT_HINTS = listOf("密码", "验证码", "password", "sign in", "log in", "登录")
    private val DASHBOARD_TEXT_HINTS = listOf(
        "余额", "额度", "用量", "账单", "充值", "令牌", "计费",
        "balance", "quota", "usage", "billing", "credits", "credit balance", "api key", "tokens used"
    )
    private val DASHBOARD_PATH_HINTS = listOf("/dashboard", "/console", "/overview", "/usage", "/billing", "/account", "/models")

    fun looksLikeLoginPage(url: String): Boolean {
        val path = url.toHttpUrlOrNull()?.let { it.encodedPath + "#" + it.encodedFragment.orEmpty() }.orEmpty()
            .lowercase(Locale.US)
        return LOGIN_PATH_HINTS.any(path::contains)
    }

    fun looksLikeDashboard(url: String, pageText: String): Boolean {
        val parsed: HttpUrl? = url.toHttpUrlOrNull()
        if (parsed == null) return false
        if (looksLikeLoginPage(url)) return false

        val text = pageText.lowercase(Locale.US)
        if (text.isBlank()) {
            val path = (parsed.encodedPath + "#" + parsed.encodedFragment.orEmpty()).lowercase(Locale.US)
            return DASHBOARD_PATH_HINTS.any(path::contains)
        }

        val dashboardHits = DASHBOARD_TEXT_HINTS.count(text::contains)
        if (dashboardHits < 2) return false
        val loginHits = LOGIN_TEXT_HINTS.count(text::contains)
        return dashboardHits > loginHits
    }
}
