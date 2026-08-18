package com.balancesentinel.app.data.update

import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

class ApkDownloader(
    private val client: OkHttpClient = defaultClient()
) {
    suspend fun downloadToPart(
        target: DownloadTarget,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): DownloadFileResult = withContext(Dispatchers.IO) {
        target.temporaryFile.parentFile?.mkdirs()
        target.temporaryFile.delete()
        val request = Request.Builder()
            .url(target.sourceUrl)
            .header("User-Agent", "BalanceSentinel-Android")
            .get()
            .build()
        val call = client.newCall(request)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext fail(
                        target.temporaryFile,
                        DownloadFailureCode.HTTP,
                        "HTTP ${response.code}"
                    )
                }
                val body = response.body ?: return@withContext fail(
                    target.temporaryFile,
                    DownloadFailureCode.EMPTY_BODY,
                    "Response body is empty"
                )
                val contentLength = body.contentLength().takeIf { it >= 0L }
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(target.temporaryFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            onProgress(downloadedBytes, contentLength)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                if (contentLength != null && downloadedBytes != contentLength) {
                    return@withContext fail(
                        target.temporaryFile,
                        DownloadFailureCode.LENGTH_MISMATCH,
                        "Expected $contentLength bytes but received $downloadedBytes"
                    )
                }
                if (!isValidApk(target.temporaryFile)) {
                    return@withContext fail(
                        target.temporaryFile,
                        DownloadFailureCode.INVALID_APK,
                        "Downloaded file is not a valid APK"
                    )
                }
                DownloadFileResult.ReadyToPublish(target.temporaryFile, downloadedBytes)
            }
        } catch (cancelled: CancellationException) {
            target.temporaryFile.delete()
            throw cancelled
        } catch (error: IOException) {
            runCatching { Logger.w(TAG, "APK download failed: ${error.message}", error) }
            fail(
                target.temporaryFile,
                DownloadFailureCode.IO,
                error.message ?: "I/O failure"
            )
        } finally {
            cancellationHandle?.dispose()
        }
    }

    fun publishAtomically(temporaryFile: File, targetFile: File): DownloadFileResult {
        return try {
            val temporaryParent = temporaryFile.canonicalFile.parentFile
            val targetParent = targetFile.canonicalFile.parentFile
            require(temporaryParent == targetParent) { "APK publication must remain in one directory" }
            require(isValidApk(temporaryFile)) { "Temporary file is not a valid APK" }
            targetParent?.mkdirs()
            Files.move(
                temporaryFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            runCatching {
                Logger.i(TAG, "APK published: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            }
            DownloadFileResult.Published(targetFile, targetFile.length())
        } catch (error: Exception) {
            temporaryFile.delete()
            DownloadFileResult.Failure(
                DownloadFailureCode.PUBLISH,
                error.message ?: "Atomic publication failed"
            )
        }
    }

    /** Compatibility entry point for callers that do not yet persist an operation. */
    suspend fun download(
        release: GitHubRelease,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): DownloadResult {
        val sourceUrl = resolveDownloadUrl(release)
            ?: return DownloadResult.Error("APK download URL is missing")
        val apkDirectory = File(cacheDir, "apk").apply { mkdirs() }
        val targetFile = File(apkDirectory, "update-${safeTag(release.tagName)}.apk")
        val temporaryFile = File(apkDirectory, "${UUID.randomUUID()}.apk.part")
        val target = DownloadTarget(UUID.randomUUID().toString(), sourceUrl, temporaryFile, targetFile)
        return when (val downloaded = downloadToPart(target) { bytes, total ->
            if (total != null && total > 0L) onProgress(bytes.toFloat() / total.toFloat())
        }) {
            is DownloadFileResult.ReadyToPublish -> when (
                val published = publishAtomically(downloaded.temporaryFile, targetFile)
            ) {
                is DownloadFileResult.Published -> DownloadResult.Success(published.file)
                is DownloadFileResult.Failure -> DownloadResult.Error(published.message)
                is DownloadFileResult.ReadyToPublish -> error("Unexpected publication result")
            }
            is DownloadFileResult.Failure -> DownloadResult.Error(downloaded.message)
            is DownloadFileResult.Published -> DownloadResult.Success(downloaded.file)
        }
    }

    fun resolveDownloadUrl(release: GitHubRelease): String? {
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        if (asset != null) return asset.downloadUrl
        return release.tagName.takeIf(String::isNotBlank)?.let { tag ->
            "https://github.com/shengbuding/balance-sentinel/releases/download/$tag/balance-sentinel-$tag.apk"
        }
    }

    private fun isValidApk(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() <= 0L) return@runCatching false
        ZipFile(file).use { archive ->
            archive.getEntry("AndroidManifest.xml")?.isDirectory == false
        }
    }.getOrDefault(false)

    private fun fail(file: File, code: String, message: String): DownloadFileResult.Failure {
        file.delete()
        return DownloadFileResult.Failure(code, message)
    }

    companion object {
        private const val TAG = "ApkDownloader"

        fun safeTag(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "release" }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
