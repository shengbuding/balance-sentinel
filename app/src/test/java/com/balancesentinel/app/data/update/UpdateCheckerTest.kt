package com.balancesentinel.app.data.update

import com.balancesentinel.app.data.model.GitHubAsset
import com.balancesentinel.app.data.model.GitHubRelease
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // We can't easily inject the URL, so we test extractSemver/compareSemver directly
        // and test fetchReleases indirectly via the inner method's URL
        checker = UpdateChecker()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── extractSemver tests ──

    @Test
    fun `extractSemver parses v-prefixed tag`() {
        val result = UpdateChecker.extractSemver("v1.2.3")
        assertEquals(Triple(1, 2, 3), result)
    }

    @Test
    fun `extractSemver parses tag without v prefix`() {
        val result = UpdateChecker.extractSemver("1.2.3")
        assertEquals(Triple(1, 2, 3), result)
    }

    @Test
    fun `extractSemver ignores git describe suffixes`() {
        val result = UpdateChecker.extractSemver("v1.0.0-3-gabc1234-dirty")
        assertEquals(Triple(1, 0, 0), result)
    }

    @Test
    fun `extractSemver ignores pre-release suffixes`() {
        val result = UpdateChecker.extractSemver("v1.2.3-beta.1")
        assertEquals(Triple(1, 2, 3), result)
    }

    @Test
    fun `extractSemver returns null for non-semver tags`() {
        val result = UpdateChecker.extractSemver("release-2026")
        assertNull(result)
    }

    @Test
    fun `extractSemver returns null for empty string`() {
        assertNull(UpdateChecker.extractSemver(""))
    }

    @Test
    fun `extractSemver handles multi-digit versions`() {
        val result = UpdateChecker.extractSemver("v10.20.300")
        assertEquals(Triple(10, 20, 300), result)
    }

    @Test
    fun `extractSemver extracts first semver match only`() {
        val result = UpdateChecker.extractSemver("prefix-1.2.3-suffix-4.5.6")
        assertEquals(Triple(1, 2, 3), result)
    }

    // ── compareSemver tests ──

    @Test
    fun `compareSemver returns positive when a has higher major`() {
        val result = UpdateChecker.compareSemver(Triple(2, 0, 0), Triple(1, 9, 9))
        assertTrue(result > 0)
    }

    @Test
    fun `compareSemver returns negative when a has lower major`() {
        val result = UpdateChecker.compareSemver(Triple(1, 0, 0), Triple(2, 0, 0))
        assertTrue(result < 0)
    }

    @Test
    fun `compareSemver compares minor when major equal`() {
        val result = UpdateChecker.compareSemver(Triple(1, 5, 0), Triple(1, 3, 9))
        assertTrue(result > 0)
    }

    @Test
    fun `compareSemver compares patch when major and minor equal`() {
        val result = UpdateChecker.compareSemver(Triple(1, 2, 5), Triple(1, 2, 3))
        assertTrue(result > 0)
    }

    @Test
    fun `compareSemver returns zero for equal versions`() {
        val result = UpdateChecker.compareSemver(Triple(1, 2, 3), Triple(1, 2, 3))
        assertEquals(0, result)
    }

    @Test
    fun `compareSemver returns negative when current is dev build ahead of latest`() {
        // Example: current dev is 2.0.0, latest release is 1.9.0
        val result = UpdateChecker.compareSemver(Triple(1, 9, 0), Triple(2, 0, 0))
        assertTrue(result < 0)
    }

    // ═══════════════════════════════════════════════════════════
    // classifyError — tested via reflection
    // ═══════════════════════════════════════════════════════════

    private fun classifyError(e: Exception): String {
        val method = UpdateChecker::class.java.getDeclaredMethod("classifyError", Exception::class.java)
        method.isAccessible = true
        return method.invoke(checker, e) as String
    }

    @Test
    fun `classifyError SocketTimeoutException`() {
        val result = classifyError(java.net.SocketTimeoutException("timeout"))
        assertEquals("连接超时，请稍后重试", result)
    }

    @Test
    fun `classifyError ConnectException`() {
        val result = classifyError(java.net.ConnectException("refused"))
        assertEquals("网络不可用，请检查连接", result)
    }

    @Test
    fun `classifyError UnknownHostException`() {
        val result = classifyError(java.net.UnknownHostException("no DNS"))
        assertEquals("网络不可用，请检查连接", result)
    }

    @Test
    fun `classifyError IOException with 403`() {
        val result = classifyError(java.io.IOException("HTTP 403 Forbidden"))
        assertEquals("GitHub API 请求过于频繁，请稍后重试", result)
    }

    @Test
    fun `classifyError IOException with HTTP 4xx`() {
        val result = classifyError(java.io.IOException("HTTP 404 Not Found"))
        assertEquals("请求失败 (404)", result)
    }

    @Test
    fun `classifyError IOException with HTTP 5xx`() {
        val result = classifyError(java.io.IOException("HTTP 503 Service Unavailable"))
        assertEquals("GitHub 服务异常，请稍后重试", result)
    }

    @Test
    fun `classifyError IOException generic`() {
        val result = classifyError(java.io.IOException("Something went wrong"))
        assertEquals("检查失败：Something went wrong", result)
    }

    @Test
    fun `classifyError generic exception`() {
        val result = classifyError(RuntimeException("Unexpected error"))
        assertEquals("检查失败：Unexpected error", result)
    }

    @Test
    fun `classifyError generic exception with null message`() {
        val result = classifyError(RuntimeException())
        assertEquals("检查失败：未知错误", result)
    }
}
