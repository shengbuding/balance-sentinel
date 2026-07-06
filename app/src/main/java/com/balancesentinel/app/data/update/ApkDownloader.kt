package com.balancesentinel.app.data.update

import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.util.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

class ApkDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Download the APK from the release's assets.
     * Falls back to the direct release download URL if no APK asset found.
     * @param onProgress callback with 0.0..1.0 progress
     */
    suspend fun download(
        release: GitHubRelease,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): DownloadResult {
        val downloadUrl = resolveDownloadUrl(release)

        if (downloadUrl == null) {
            return DownloadResult.Error("未找到 APK 下载地址")
        }

        val apkDir = File(cacheDir, "apk")
        if (!apkDir.exists()) apkDir.mkdirs()

        val fileName = "update-${release.tagName}.apk"
        val apkFile = File(apkDir, fileName)

        // Clean up any previous partial download
        apkFile.delete()

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "BalanceSentinel-Android")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadResult.Error("下载失败：HTTP ${response.code}")
                }

                val body = response.body ?: return DownloadResult.Error("下载失败：响应为空")

                val contentLength = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (contentLength > 0) {
                                onProgress(downloadedBytes.toFloat() / contentLength.toFloat())
                            }
                        }
                    }
                }

                if (contentLength > 0 && apkFile.length() < contentLength) {
                    apkFile.delete()
                    return DownloadResult.Error("下载不完整，请重试")
                }

                Logger.i(TAG, "APK downloaded: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
                return DownloadResult.Success(apkFile)
            }
        } catch (e: IOException) {
            apkFile.delete()
            Logger.w(TAG, "APK download failed: ${e.message}", e)
            return DownloadResult.Error("下载失败：${e.message ?: "网络异常"}")
        }
    }

    /**
     * Find the APK download URL from release assets.
     * Fallback: {tag}/app-release.apk direct URL pattern.
     */
    private fun resolveDownloadUrl(release: GitHubRelease): String? {
        val apkAsset = release.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk")
        }
        if (apkAsset != null) return apkAsset.downloadUrl

        // Fallback — construct URL from tag name
        val tag = release.tagName
        if (tag.isNotEmpty()) {
            return "https://github.com/shengbuding/balance-sentinel/releases/download/$tag/app-release.apk"
        }
        return null
    }

    companion object {
        private const val TAG = "ApkDownloader"
    }
}
