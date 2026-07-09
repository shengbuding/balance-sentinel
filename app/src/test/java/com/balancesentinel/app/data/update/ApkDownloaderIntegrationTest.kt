@file:Suppress("DEPRECATION")
package com.balancesentinel.app.data.update

import com.balancesentinel.app.data.model.GitHubAsset
import com.balancesentinel.app.data.model.GitHubRelease
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ApkDownloaderIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: ApkDownloader
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ApkDownloader()
        tempDir = createTempDir("apk-test")
        injectMockServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun injectMockServer() {
        val mockBase = server.url("/").toString().trimEnd('/')
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val newUrl = original.url.toString()
                    .replace("https://github.com", mockBase)
                chain.proceed(original.newBuilder().url(newUrl).build())
            })
            .build()

        val clientField = ApkDownloader::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(downloader, mockClient)
    }

    private fun createDownloadRelease(): GitHubRelease {
        return GitHubRelease(
            tagName = "v1.0.0",
            name = "Release v1.0.0",
            body = "",
            prerelease = false,
            publishedAt = "2026-01-01T00:00:00Z",
            htmlUrl = "https://github.com/test/releases/v1.0.0",
            assets = listOf(
                GitHubAsset(
                    name = "app-release.apk",
                    downloadUrl = "https://github.com/shengbuding/balance-sentinel/releases/download/v1.0.0/app-release.apk",
                    size = 100
                )
            )
        )
    }

    @Test
    fun `download returns Error when no APK asset found`() = runTest {
        // Release with empty tag and no assets → resolveDownloadUrl returns null
        val release = GitHubRelease(
            tagName = "",
            name = "Release",
            body = "",
            prerelease = false,
            publishedAt = "",
            htmlUrl = "",
            assets = emptyList()
        )

        var progressCalled = false
        val result = downloader.download(release, tempDir) { progressCalled = true }

        assertTrue(result is DownloadResult.Error)
        assertTrue((result as DownloadResult.Error).message.contains("APK"))
        assertFalse(progressCalled)
    }

    @Test
    fun `download returns Error on HTTP failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val release = createDownloadRelease()
        val result = downloader.download(release, tempDir) {}

        assertTrue(result is DownloadResult.Error)
    }

    @Test
    fun `download succeeds with valid response`() = runTest {
        val apkData = ByteArray(100) { it.toByte() }
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(okio.Buffer().write(apkData))
            .setHeader("Content-Length", "100")
        )

        val release = createDownloadRelease()
        var lastProgress = -1f
        val result = downloader.download(release, tempDir) { progress ->
            lastProgress = progress
        }

        assertTrue(result is DownloadResult.Success)
        val file = (result as DownloadResult.Success).file
        assertTrue(file.exists())
        assertEquals(100, file.length())
        assertTrue(lastProgress > 0f)
    }

    @Test
    fun `download returns Error when download incomplete`() = runTest {
        // Server reports 200 bytes but only sends 100
        val apkData = ByteArray(100) { it.toByte() }
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(okio.Buffer().write(apkData))
            .setHeader("Content-Length", "200")
        )

        val release = createDownloadRelease()
        val result = downloader.download(release, tempDir) {}

        assertTrue(result is DownloadResult.Error)
    }
}
