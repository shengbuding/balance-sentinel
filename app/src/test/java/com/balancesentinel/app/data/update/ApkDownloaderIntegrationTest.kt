package com.balancesentinel.app.data.update

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkDownloaderIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = createTempDir(prefix = "apk-downloader-")
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test
    fun `valid apk is validated before atomic publication`() = runTest {
        val apk = validApkBytes()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val part = File(directory, "op.part")
        val target = File(directory, "release.apk")
        val downloader = ApkDownloader(OkHttpClient())

        val result = downloader.downloadToPart(
            DownloadTarget("op", server.url("/release.apk").toString(), part, target)
        ) { _, _ -> }

        assertTrue(result is DownloadFileResult.ReadyToPublish)
        assertFalse(target.exists())
        val published = downloader.publishAtomically(part, target)
        assertTrue(published.toString(), published is DownloadFileResult.Published)
        assertTrue(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `non apk is rejected without publishing target`() = runTest {
        server.enqueue(MockResponse().setBody("not-an-apk"))
        val part = File(directory, "bad.part")
        val target = File(directory, "release.apk")
        val downloader = ApkDownloader(OkHttpClient())

        val result = downloader.downloadToPart(
            DownloadTarget("bad", server.url("/bad.apk").toString(), part, target)
        ) { _, _ -> }

        assertTrue(result is DownloadFileResult.Failure)
        assertFalse(part.exists())
        assertFalse(target.exists())
    }

    @Test
    fun `cancellation removes only current operation part`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(512 * 1024)))
                .throttleBody(1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        val part = File(directory, "current.part")
        val otherPart = File(directory, "other.part").apply { writeText("keep") }
        val published = File(directory, "published.apk").apply { writeText("keep") }
        val downloader = ApkDownloader(OkHttpClient())

        val job = async {
            downloader.downloadToPart(
                DownloadTarget("current", server.url("/slow.apk").toString(), part, File(directory, "new.apk"))
            ) { _, _ -> }
        }
        delay(150)
        job.cancelAndJoin()

        assertFalse(part.exists())
        assertTrue(otherPart.exists())
        assertTrue(published.exists())
    }

    private fun validApkBytes(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(4, 5, 6))
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }
}
