package com.balancesentinel.app.ui.console

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolePlatformDraftTest {
    @Test
    fun `bare domain is normalized to https`() {
        assertEquals("https://ai.gitee.com/", ConsolePlatformDraft.normalizeHttpsUrl("ai.gitee.com"))
        assertEquals(
            "https://ai.gitee.com/#/console/usage",
            ConsolePlatformDraft.normalizeHttpsUrl("https://ai.gitee.com/#/console/usage")
        )
    }

    @Test
    fun `plain http is rejected instead of upgraded`() {
        assertNull(ConsolePlatformDraft.normalizeHttpsUrl("http://ai.gitee.com/"))
        assertNull(ConsolePlatformDraft.normalizeHttpsUrl("not a url"))
        assertNull(ConsolePlatformDraft.normalizeHttpsUrl(""))
    }

    @Test
    fun `name alone is not enough to save`() {
        val draft = ConsolePlatformDraft(name = "中转站")

        assertEquals(
            setOf(ConsoleDraftError.UrlsMissing),
            draft.errors().minus(ConsoleDraftError.NameMissing)
        )
        assertTrue(ConsoleDraftError.UrlsMissing in draft.errors())
        assertFalse(draft.isValid)
    }

    @Test
    fun `website only draft is valid and reuses website for both pages`() {
        val draft = ConsolePlatformDraft(name = "中转站", website = "ai.gitee.com")

        assertFalse(ConsoleDraftError.UrlsMissing in draft.errors())
        assertTrue(draft.isValid)

        val platform = draft.toPlatform(platformId = "custom_1", isPreset = false)
        assertEquals("https://ai.gitee.com/", platform.loginUrl)
        assertEquals("https://ai.gitee.com/", platform.dashboardUrl)
        assertEquals(listOf("/"), platform.successUrlPatterns)
        assertEquals(ConsolePlatformDraft.DEFAULT_LOGIN_PATTERNS, platform.loginPagePatterns)
        assertFalse(platform.isPreset)
        assertNull(platform.description)
    }

    @Test
    fun `capture fills in marked pages without touching what the user typed`() {
        val draft = ConsolePlatformDraft(name = "中转站", website = "ai.gitee.com")

        val merged = draft.applyCapture(
            ConsoleCaptureResult(
                loginUrl = "https://ai.gitee.com/#/login",
                dashboardUrl = "https://ai.gitee.com/#/console/usage",
                successPatterns = listOf("/#/console/usage"),
                loginPatterns = listOf("/#/login")
            )
        )

        assertEquals("https://ai.gitee.com/#/login", merged.loginUrl)
        assertEquals("https://ai.gitee.com/#/console/usage", merged.dashboardUrl)
        assertEquals("/#/console/usage", merged.successPatterns)
        assertEquals("/#/login", merged.loginPatterns)
        assertEquals("ai.gitee.com", merged.website)
    }

    @Test
    fun `empty capture keeps hand written values`() {
        val draft = ConsolePlatformDraft(
            name = "中转站",
            website = "ai.gitee.com",
            loginUrl = "https://ai.gitee.com/#/login",
            dashboardUrl = "https://ai.gitee.com/#/console/usage",
            successPatterns = "/#/console/usage"
        )

        val merged = draft.applyCapture(ConsoleCaptureResult())

        assertEquals(draft.loginUrl, merged.loginUrl)
        assertEquals(draft.dashboardUrl, merged.dashboardUrl)
        assertEquals("/#/console/usage", merged.successPatterns)
    }

    @Test
    fun `editing an existing platform keeps its identity and patterns`() {
        val platform = ConsolePlatform(
            id = "custom_1",
            name = "中转站",
            loginUrl = "https://ai.gitee.com/#/login",
            dashboardUrl = "https://ai.gitee.com/#/console/usage",
            successUrlPatterns = listOf("/#/console/usage"),
            loginPagePatterns = listOf("/#/login"),
            description = "备用"
        )

        val edited = ConsolePlatformDraft.fromPlatform(platform)
            .copy(name = "中转站二号")
            .toPlatform(platformId = platform.id, isPreset = false)

        assertEquals("custom_1", edited.id)
        assertEquals("中转站二号", edited.name)
        assertEquals(listOf("/#/console/usage"), edited.successUrlPatterns)
        assertEquals(listOf("/#/login"), edited.loginPagePatterns)
        assertEquals("备用", edited.description)
        assertEquals("ai.gitee.com/", ConsolePlatformDraft.fromPlatform(platform).website)
    }

    @Test
    fun `derivePattern keeps hash routes and trims long paths`() {
        assertEquals(
            "/#/console/usage",
            ConsolePlatformDraft.derivePattern("https://ai.gitee.com/#/console/usage".toHttpUrl())
        )
        assertEquals(
            "/overview",
            ConsolePlatformDraft.derivePattern("https://platform.deepseek.com/overview".toHttpUrl())
        )
        assertEquals(
            "/a/b/c",
            ConsolePlatformDraft.derivePattern("https://example.com/a/b/c/d/e".toHttpUrl())
        )
        assertEquals(
            "/",
            ConsolePlatformDraft.derivePattern("https://example.com".toHttpUrl())
        )
    }

    @Test
    fun `pattern parsing tolerates commas newlines and blanks`() {
        assertEquals(
            listOf("/a", "/b", "/c"),
            ConsolePlatformDraft.splitPatterns(" /a , /b\n/a\n\n/c ")
        )
        assertEquals("/a,/b", ConsolePlatformDraft.joinPatterns(listOf(" /a ", "/b", "/a", "")))
    }
}
