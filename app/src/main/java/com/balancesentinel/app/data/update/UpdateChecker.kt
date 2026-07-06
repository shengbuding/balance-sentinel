package com.balancesentinel.app.data.update

import android.content.Context
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed class UpdateResult {
    data object UpToDate : UpdateResult()
    data class UpdateAvailable(val release: GitHubRelease, val currentVersion: String) : UpdateResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : UpdateResult()
}

class UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(context: Context): UpdateResult {
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }

        val releases: List<GitHubRelease>
        try {
            releases = fetchReleases()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to fetch releases: ${e.message}", e)
            return UpdateResult.Error(classifyError(e), isNetworkError = true)
        }

        val stableRelease = releases.firstOrNull { !it.prerelease }
            ?: return UpdateResult.Error("暂无正式版本发布")

        val currentSemver = extractSemver(currentVersion)
        val latestSemver = extractSemver(stableRelease.tagName)

        if (currentSemver == null || latestSemver == null) {
            Logger.w(TAG, "Semver parse failed: current=$currentVersion, latest=${stableRelease.tagName}")
            return UpdateResult.UpToDate
        }

        if (compareSemver(latestSemver, currentSemver) > 0) {
            return UpdateResult.UpdateAvailable(stableRelease, currentVersion)
        }

        return UpdateResult.UpToDate
    }

    @Throws(IOException::class)
    private fun fetchReleases(): List<GitHubRelease> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/shengbuding/balance-sentinel/releases")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BalanceSentinel-Android")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IOException("Empty response body")

            if (!response.isSuccessful) {
                throw IOException("GitHub API returned HTTP ${response.code}")
            }

            return json.decodeFromString<List<GitHubRelease>>(body)
        }
    }

    private fun classifyError(e: Exception): String {
        return when (e) {
            is SocketTimeoutException -> "连接超时，请稍后重试"
            is ConnectException, is UnknownHostException -> "网络不可用，请检查连接"
            is IOException -> {
                val msg = e.message ?: ""
                when {
                    msg.contains("403") -> "GitHub API 请求过于频繁，请稍后重试"
                    msg.contains("HTTP 4") -> "请求失败 (${msg.substringAfter("HTTP ").take(3)})"
                    msg.contains("HTTP 5") -> "GitHub 服务异常，请稍后重试"
                    else -> "检查失败：$msg"
                }
            }
            else -> "检查失败：${e.message ?: "未知错误"}"
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"

        /**
         * Extract X.Y.Z semver core from a tag string.
         * Returns Triple(major, minor, patch) or null if unparseable.
         */
        fun extractSemver(tag: String): Triple<Int, Int, Int>? {
            val regex = Regex("""(\d+)\.(\d+)\.(\d+)""")
            val match = regex.find(tag) ?: return null
            val (major, minor, patch) = match.destructured
            return try {
                Triple(major.toInt(), minor.toInt(), patch.toInt())
            } catch (_: NumberFormatException) {
                null
            }
        }

        /**
         * Compare two semver triples.
         * Returns positive if a > b, negative if a < b, 0 if equal.
         */
        fun compareSemver(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
            if (a.first != b.first) return a.first - b.first
            if (a.second != b.second) return a.second - b.second
            return a.third - b.third
        }
    }
}
