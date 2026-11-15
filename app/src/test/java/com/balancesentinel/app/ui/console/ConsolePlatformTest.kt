package com.balancesentinel.app.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolePlatformTest {
    @Test
    fun `login success rejects a host suffix spoof`() {
        assertFalse(PLATFORM.isLoginSuccess("https://dashboard.example.com.evil.test/overview"))
    }

    @Test
    fun `login success ignores dashboard patterns in query values`() {
        assertFalse(PLATFORM.isLoginSuccess("https://dashboard.example.com/?next=/overview"))
    }

    @Test
    fun `login success accepts dashboard path on exact configured origin`() {
        assertTrue(PLATFORM.isLoginSuccess("https://dashboard.example.com/overview"))
    }

    @Test
    fun `login success rejects login path on exact configured origin`() {
        assertFalse(PLATFORM.isLoginSuccess("https://dashboard.example.com/login"))
    }

    @Test
    fun `patterns match whole path segments only`() {
        assertTrue(SEGMENTED.isLoginPage("https://login.example.com/login/more"))
        assertFalse(SEGMENTED.isLoginPage("https://login.example.com/login-archive"))
        assertTrue(PLATFORM.isLoginSuccess("https://dashboard.example.com/overview/usage"))
        assertFalse(PLATFORM.isLoginSuccess("https://dashboard.example.com/overview-archive"))
    }

    @Test
    fun `a root login pattern matches only the site root`() {
        // 首页即登录页的站点会把 "/" 记进登录页模式。
        // 这种模式只能命中站点根，不能把任何地址都判成登录页。
        assertTrue(HOME_LOGIN_PLATFORM.isLoginPage("https://console.example.com/"))
        assertFalse(HOME_LOGIN_PLATFORM.isLoginPage("https://console.example.com/dashboard"))
        assertFalse(HOME_LOGIN_PLATFORM.isLoginPage("https://console.example.com/#/console/usage"))
    }

    @Test
    fun `a home login platform never reports an expired login`() {
        // 已登录和未登录看到的是同一个地址，单看 URL 无法区分，只能不提示。
        assertFalse(HOME_LOGIN_PLATFORM.reportsLoginExpiryAt("https://console.example.com/"))
        assertFalse(HOME_LOGIN_PLATFORM.reportsLoginExpiryAt("https://console.example.com/dashboard"))
    }

    @Test
    fun `a normal platform still reports an expired login on its login page`() {
        assertTrue(SEGMENTED.reportsLoginExpiryAt("https://login.example.com/login"))
        assertFalse(PLATFORM.reportsLoginExpiryAt("https://dashboard.example.com/overview"))
    }

    @Test
    fun `preferred dashboard url returns to the last visited page`() {
        assertEquals(
            "https://dashboard.example.com/overview/usage",
            PLATFORM.preferredDashboardUrl("https://dashboard.example.com/overview/usage")
        )
    }

    @Test
    fun `preferred dashboard url ignores a foreign or login page`() {
        assertEquals(
            "https://dashboard.example.com/overview",
            PLATFORM.preferredDashboardUrl("https://evil.test/overview")
        )
        assertEquals(
            "https://dashboard.example.com/overview",
            PLATFORM.preferredDashboardUrl("https://dashboard.example.com/login")
        )
        assertEquals("https://dashboard.example.com/overview", PLATFORM.preferredDashboardUrl(null))
    }

    @Test
    fun `a site with only a home dashboard falls back to its success path`() {
        // 只填了网站（首页）的平台：直接进首页等于让用户每次自己再点一次。
        assertEquals("https://console.example.com/dashboard", HOME_LOGIN_PLATFORM.preferredDashboardUrl(null))
    }

    @Test
    fun `rememberable urls must stay on the dashboard origin`() {
        assertTrue(PLATFORM.isRememberableDashboardUrl("https://dashboard.example.com/overview"))
        assertFalse(PLATFORM.isRememberableDashboardUrl("https://dashboard.example.com.evil.test/overview"))
        assertFalse(PLATFORM.isRememberableDashboardUrl("http://dashboard.example.com/overview"))
        assertFalse(PLATFORM.isRememberableDashboardUrl("https://dashboard.example.com/login"))
        assertFalse(PLATFORM.isRememberableDashboardUrl(null))
    }

    private companion object {
        val PLATFORM = ConsolePlatform(
            id = "custom",
            name = "Custom",
            loginUrl = "https://login.example.com/sign-in",
            dashboardUrl = "https://dashboard.example.com/overview",
            successUrlPatterns = listOf("dashboard.example.com/overview")
        )

        /** 登录路径是 /login，用来验证路径段边界匹配 */
        val SEGMENTED = ConsolePlatform(
            id = "segmented",
            name = "Segmented",
            loginUrl = "https://login.example.com/login",
            dashboardUrl = "https://dashboard.example.com/overview",
            successUrlPatterns = listOf("dashboard.example.com/overview")
        )

        /** 首页即登录页：登录页模式里带着 "/" */
        val HOME_LOGIN_PLATFORM = ConsolePlatform(
            id = "home",
            name = "Home",
            loginUrl = "https://console.example.com/",
            dashboardUrl = "https://console.example.com/",
            successUrlPatterns = listOf("console.example.com/dashboard"),
            loginPagePatterns = listOf("/")
        )
    }
}
