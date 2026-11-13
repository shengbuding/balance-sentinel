package com.balancesentinel.app.data.console

import com.balancesentinel.app.ui.console.ConsolePageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolePageCaptureSessionTest {
    private var clock = 1_000L
    private val session = ConsolePageCaptureSession(now = { clock })

    @Test
    fun `manual marks record pattern and timestamp`() {
        assertTrue(session.mark(ConsolePageKind.LOGIN, "https://ai.gitee.com/#/login"))

        val page = session.markedPages.single()
        assertEquals(ConsolePageKind.LOGIN, page.kind)
        assertEquals("/#/login", page.pattern)
        assertEquals("https://ai.gitee.com/#/login", page.url)
        assertEquals(1_000L, page.markedAt)
    }

    @Test
    fun `duplicate mark of same kind and pattern is ignored`() {
        assertTrue(session.mark(ConsolePageKind.DASHBOARD, "https://ai.gitee.com/console/usage"))
        clock = 2_000L
        assertFalse(session.mark(ConsolePageKind.DASHBOARD, "https://ai.gitee.com/console/usage?tab=1"))

        assertEquals(1, session.markedPages.size)
        // 同一个页面另一种用途仍然可以记
        assertTrue(session.mark(ConsolePageKind.LOGIN, "https://ai.gitee.com/console/usage"))
        assertEquals(2, session.markedPages.size)
    }

    @Test
    fun `non https urls are never marked`() {
        assertFalse(session.mark(ConsolePageKind.LOGIN, "http://ai.gitee.com/#/login"))
        assertFalse(session.mark(ConsolePageKind.LOGIN, "about:blank"))
        assertFalse(session.mark(ConsolePageKind.LOGIN, ""))
        assertTrue(session.markedPages.isEmpty())
    }

    @Test
    fun `automatic marks stop at the limit but manual marks do not`() {
        val limited = ConsolePageCaptureSession(maxAutoMarks = 2, now = { clock })

        assertTrue(limited.mark(ConsolePageKind.DASHBOARD, "https://a.example.com/1", auto = true))
        assertTrue(limited.mark(ConsolePageKind.DASHBOARD, "https://a.example.com/2", auto = true))
        assertFalse(limited.mark(ConsolePageKind.DASHBOARD, "https://a.example.com/3", auto = true))
        assertTrue(limited.autoMarkLimitReached)
        assertEquals(2, limited.autoMarkCount)

        assertTrue(limited.mark(ConsolePageKind.DASHBOARD, "https://a.example.com/3"))
        assertEquals(3, limited.markedPages.size)
    }

    @Test
    fun `result keeps the last mark per kind and dedupes patterns`() {
        session.mark(ConsolePageKind.LOGIN, "https://ai.gitee.com/#/login")
        session.mark(ConsolePageKind.DASHBOARD, "https://ai.gitee.com/#/console/usage")
        session.mark(ConsolePageKind.DASHBOARD, "https://ai.gitee.com/#/console/billing")

        val result = session.result()

        assertEquals("https://ai.gitee.com/#/login", result.loginUrl)
        assertEquals("https://ai.gitee.com/#/console/billing", result.dashboardUrl)
        assertEquals(listOf("/#/console/usage", "/#/console/billing"), result.successPatterns)
        assertEquals(listOf("/#/login"), result.loginPatterns)
    }

    @Test
    fun `empty session yields an empty capture`() {
        val result = session.result()

        assertNull(result.loginUrl)
        assertNull(result.dashboardUrl)
        assertTrue(result.successPatterns.isEmpty())
        assertTrue(result.loginPatterns.isEmpty())
    }

    @Test
    fun `unmark and clear drop marks`() {
        session.mark(ConsolePageKind.LOGIN, "https://ai.gitee.com/#/login")
        session.mark(ConsolePageKind.DASHBOARD, "https://ai.gitee.com/#/console/usage")

        assertTrue(session.unmark(session.markedPages.first()))
        assertEquals(1, session.markedPages.size)
        assertFalse(session.unmark(session.markedPages.first().copy(pattern = "/#/missing")))

        session.clear()
        assertTrue(session.markedPages.isEmpty())
        assertEquals(0, session.autoMarkCount)
    }

    @Test
    fun `same site allows subdomains in both directions only`() {
        assertTrue(ConsolePageCaptureSession.isSameSite("https://ai.gitee.com/", "https://ai.gitee.com/login"))
        assertTrue(ConsolePageCaptureSession.isSameSite("https://gitee.com/", "https://ai.gitee.com/login"))
        assertTrue(ConsolePageCaptureSession.isSameSite("https://ai.gitee.com/", "https://gitee.com/login"))
        assertFalse(ConsolePageCaptureSession.isSameSite("https://ai.gitee.com/", "https://evil.example.com/"))
        assertFalse(ConsolePageCaptureSession.isSameSite("not a url", "https://ai.gitee.com/"))
    }

    @Test
    fun `login looking urls are recognised`() {
        assertTrue(ConsoleAutoMarkHeuristics.looksLikeLoginPage("https://ai.gitee.com/#/login"))
        assertTrue(ConsoleAutoMarkHeuristics.looksLikeLoginPage("https://x.example.com/sign_in?next=/console"))
        assertTrue(ConsoleAutoMarkHeuristics.looksLikeLoginPage("https://x.example.com/oauth/authorize"))
        assertFalse(ConsoleAutoMarkHeuristics.looksLikeLoginPage("https://ai.gitee.com/#/console/usage"))
    }

    @Test
    fun `dashboard detection needs quota evidence and is never a login page`() {
        assertFalse(ConsoleAutoMarkHeuristics.looksLikeDashboard("https://ai.gitee.com/#/login", "余额 用量"))
        assertFalse(ConsoleAutoMarkHeuristics.looksLikeDashboard("https://ai.gitee.com/#/console", "欢迎回来"))
        assertFalse(
            ConsoleAutoMarkHeuristics.looksLikeDashboard(
                "https://ai.gitee.com/#/console",
                "请输入密码 password 登录 balance"
            )
        )
        assertTrue(
            ConsoleAutoMarkHeuristics.looksLikeDashboard(
                "https://ai.gitee.com/#/console",
                "账户余额 12.00 元，本月用量 3 次"
            )
        )
        assertTrue(
            ConsoleAutoMarkHeuristics.looksLikeDashboard(
                "https://ai.gitee.com/x",
                "Balance 12.00 USD, usage this month"
            )
        )
    }

    @Test
    fun `dashboard path hints only apply when page text is unavailable`() {
        assertTrue(ConsoleAutoMarkHeuristics.looksLikeDashboard("https://ai.gitee.com/#/console/usage", ""))
        assertFalse(ConsoleAutoMarkHeuristics.looksLikeDashboard("https://ai.gitee.com/#/console/usage", "欢迎回来"))
    }
}
