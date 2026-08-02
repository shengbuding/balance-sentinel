package com.balancesentinel.app.ui.console

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

    private companion object {
        val PLATFORM = ConsolePlatform(
            id = "custom",
            name = "Custom",
            loginUrl = "https://login.example.com/sign-in",
            dashboardUrl = "https://dashboard.example.com/overview",
            successUrlPatterns = listOf("dashboard.example.com/overview")
        )
    }
}
