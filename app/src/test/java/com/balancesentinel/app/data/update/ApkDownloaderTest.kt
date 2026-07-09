package com.balancesentinel.app.data.update

import com.balancesentinel.app.data.model.GitHubAsset
import com.balancesentinel.app.data.model.GitHubRelease
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApkDownloaderTest {

    private lateinit var downloader: ApkDownloader

    @Before
    fun setUp() {
        downloader = ApkDownloader()
    }

    // ═══════════════════════════════════════════════════════════
    // resolveDownloadUrl — tested via reflection
    // ═══════════════════════════════════════════════════════════

    private fun resolveDownloadUrl(release: GitHubRelease): String? {
        val method = ApkDownloader::class.java.getDeclaredMethod(
            "resolveDownloadUrl", GitHubRelease::class.java
        )
        method.isAccessible = true
        return method.invoke(downloader, release) as String?
    }

    @Test
    fun `resolveDownloadUrl returns asset URL when APK asset exists`() {
        val release = GitHubRelease(
            tagName = "v1.2.0",
            name = "Release v1.2.0",
            body = "",
            prerelease = false,
            publishedAt = "2026-07-01T00:00:00Z",
            htmlUrl = "https://github.com/example/releases/v1.2.0",
            assets = listOf(
                GitHubAsset(
                    name = "app-release.apk",
                    downloadUrl = "https://github.com/example/releases/download/v1.2.0/app-release.apk",
                    size = 1024000
                )
            )
        )
        val result = resolveDownloadUrl(release)
        assertEquals("https://github.com/example/releases/download/v1.2.0/app-release.apk", result)
    }

    @Test
    fun `resolveDownloadUrl skips non-APK assets`() {
        val release = GitHubRelease(
            tagName = "v1.2.0",
            name = "Release v1.2.0",
            body = "",
            prerelease = false,
            publishedAt = "2026-07-01T00:00:00Z",
            htmlUrl = "https://github.com/example/releases/v1.2.0",
            assets = listOf(
                GitHubAsset(
                    name = "source-code.zip",
                    downloadUrl = "https://github.com/example/releases/download/v1.2.0/source.zip",
                    size = 500000
                )
            )
        )
        val result = resolveDownloadUrl(release)
        // Falls back to constructed URL
        assertEquals(
            "https://github.com/shengbuding/balance-sentinel/releases/download/v1.2.0/app-release.apk",
            result
        )
    }

    @Test
    fun `resolveDownloadUrl falls back to constructed URL when no assets`() {
        val release = GitHubRelease(
            tagName = "v2.0.0",
            name = "Release v2.0.0",
            body = "",
            prerelease = false,
            publishedAt = "2026-07-01T00:00:00Z",
            htmlUrl = "https://github.com/example/releases/v2.0.0",
            assets = emptyList()
        )
        val result = resolveDownloadUrl(release)
        assertEquals(
            "https://github.com/shengbuding/balance-sentinel/releases/download/v2.0.0/app-release.apk",
            result
        )
    }

    @Test
    fun `resolveDownloadUrl returns null when tag empty and no assets`() {
        val release = GitHubRelease(
            tagName = "",
            name = "",
            body = "",
            prerelease = false,
            publishedAt = "",
            htmlUrl = "",
            assets = emptyList()
        )
        val result = resolveDownloadUrl(release)
        assertNull(result)
    }

    @Test
    fun `resolveDownloadUrl picks first APK when multiple assets`() {
        val release = GitHubRelease(
            tagName = "v1.0.0",
            name = "Release v1.0.0",
            body = "",
            prerelease = false,
            publishedAt = "2026-07-01T00:00:00Z",
            htmlUrl = "https://github.com/example/releases/v1.0.0",
            assets = listOf(
                GitHubAsset(
                    name = "app-arm64.apk",
                    downloadUrl = "https://github.com/example/releases/download/v1.0.0/app-arm64.apk",
                    size = 2048000
                ),
                GitHubAsset(
                    name = "app-universal.apk",
                    downloadUrl = "https://github.com/example/releases/download/v1.0.0/app-universal.apk",
                    size = 3072000
                )
            )
        )
        val result = resolveDownloadUrl(release)
        assertEquals("https://github.com/example/releases/download/v1.0.0/app-arm64.apk", result)
    }
}
