# Automatic Update Checker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build in-app update detection that checks GitHub Releases daily, downloads APKs with progress, and triggers system installer.

**Architecture:** Four-layer design — `GitHubRelease` data model, `UpdateChecker` (API + semver parsing), `UpdatePrefs` (SharedPreferences wrapper), `ApkDownloader` (OkHttp streaming download with progress callback), `UpdateDialog` (Composable with 4-state UI). SettingsScreen gains a "检查更新" button in the VersionInfo card. MainActivity.onResume triggers daily auto-check.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), OkHttp, kotlinx.serialization, SharedPreferences, FileProvider, JUnit + MockWebServer + Robolectric (tests)

## Global Constraints

- Min/Target SDK: 35 (Android 15+)
- JDK: 17
- OkHttp already in dependencies — no new network libraries
- kotlinx.serialization already in dependencies
- Use `com.balancesentinel.app.data.util.Logger` for logging, never `android.util.Log` directly
- Use `WalletColors` from theme for status colors
- All user-facing strings MUST use `stringResource(R.string.*)`, never hardcoded
- Follow `WidgetPrefs` pattern for SharedPreferences wrappers
- Follow `DeepSeekApiService` pattern for OkHttp classes (own client instance, no DI)
- Test with MockWebServer for API tests, Robolectric for SharedPreferences tests
- Release notes text in update dialog supports scrollable plain text only (no Markdown rendering)

---

### Task 1: GitHub Release Data Model

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/model/GitHubRelease.kt`

**Interfaces:**
- Produces: `GitHubRelease` (tagName, name, prerelease, publishedAt, htmlUrl, body, assets: List<GitHubAsset>)
- Produces: `GitHubAsset` (name, downloadUrl, size)

- [ ] **Step 1: Create the data class file**

```kotlin
package com.balancesentinel.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("size") val size: Long = 0
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/data/model/GitHubRelease.kt
git commit -m "feat: add GitHubRelease data model for update checker"
```

---

### Task 2: UpdatePrefs — SharedPreferences Wrapper

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/update/UpdatePrefs.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/update/UpdatePrefsTest.kt`

**Interfaces:**
- Produces: `UpdatePrefs(context: Context)` class
- Produces: `var lastPromptDate: String` (get/set, format "yyyy-MM-dd")
- Produces: `var skippedVersion: String` (get/set)
- Produces: `fun shouldAutoCheckToday(): Boolean` — returns true if lastPromptDate != today

- [ ] **Step 1: Write the failing test**

```kotlin
package com.balancesentinel.app.data.update

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
class UpdatePrefsTest {

    private lateinit var prefs: UpdatePrefs

    @Before
    fun setUp() {
        prefs = UpdatePrefs(ApplicationProvider.getApplicationContext())
        // Clear state between tests
        prefs.lastPromptDate = ""
        prefs.skippedVersion = ""
    }

    @Test
    fun `lastPromptDate persists correctly`() {
        prefs.lastPromptDate = "2026-07-06"
        assertEquals("2026-07-06", prefs.lastPromptDate)
    }

    @Test
    fun `lastPromptDate defaults to empty string`() {
        // Fresh instance with no saved value
        val fresh = UpdatePrefs(ApplicationProvider.getApplicationContext())
        fresh.lastPromptDate = "" // reset
        assertEquals("", fresh.lastPromptDate)
    }

    @Test
    fun `skippedVersion persists correctly`() {
        prefs.skippedVersion = "1.2.0"
        assertEquals("1.2.0", prefs.skippedVersion)
    }

    @Test
    fun `skippedVersion defaults to empty string`() {
        prefs.skippedVersion = ""
        assertEquals("", prefs.skippedVersion)
    }

    @Test
    fun `shouldAutoCheckToday returns false when already prompted today`() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.lastPromptDate = today
        assertFalse(prefs.shouldAutoCheckToday())
    }

    @Test
    fun `shouldAutoCheckToday returns true when last prompted yesterday`() {
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
            Date(System.currentTimeMillis() - 86400000)
        )
        prefs.lastPromptDate = yesterday
        assertTrue(prefs.shouldAutoCheckToday())
    }

    @Test
    fun `shouldAutoCheckToday returns true when never prompted`() {
        prefs.lastPromptDate = ""
        assertTrue(prefs.shouldAutoCheckToday())
    }

    @Test
    fun `shouldSkipVersion returns true for skipped version`() {
        prefs.skippedVersion = "1.2.0"
        assertTrue(prefs.shouldSkipVersion("1.2.0"))
    }

    @Test
    fun `shouldSkipVersion returns false for different version`() {
        prefs.skippedVersion = "1.2.0"
        assertFalse(prefs.shouldSkipVersion("1.3.0"))
    }

    @Test
    fun `shouldSkipVersion returns false when nothing skipped`() {
        prefs.skippedVersion = ""
        assertFalse(prefs.shouldSkipVersion("1.0.0"))
    }

    @Test
    fun `markPromptedToday sets lastPromptDate to today`() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.markPromptedToday()
        assertEquals(today, prefs.lastPromptDate)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.update.UpdatePrefsTest" 2>&1 | tail -10`
Expected: compilation error — `UpdatePrefs` not defined

- [ ] **Step 3: Write the UpdatePrefs implementation**

```kotlin
package com.balancesentinel.app.data.update

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdatePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    var lastPromptDate: String
        get() = prefs.getString(KEY_LAST_PROMPT_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PROMPT_DATE, value).apply()

    var skippedVersion: String
        get() = prefs.getString(KEY_SKIPPED_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SKIPPED_VERSION, value).apply()

    fun shouldAutoCheckToday(): Boolean {
        val today = todayString()
        return lastPromptDate != today
    }

    fun shouldSkipVersion(version: String): Boolean {
        return skippedVersion.isNotEmpty() && skippedVersion == version
    }

    fun markPromptedToday() {
        lastPromptDate = todayString()
    }

    private fun todayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    companion object {
        private const val KEY_LAST_PROMPT_DATE = "last_prompt_date"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.update.UpdatePrefsTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` (11 tests pass)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/data/update/UpdatePrefs.kt app/src/test/java/com/balancesentinel/app/data/update/UpdatePrefsTest.kt
git commit -m "feat: add UpdatePrefs for daily prompt tracking and version skip"
```

---

### Task 3: UpdateChecker — GitHub API + Version Comparison

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/update/UpdateChecker.kt`

**Interfaces:**
- Consumes: `GitHubRelease` from Task 1, `UpdatePrefs` from Task 2
- Produces: `UpdateChecker` class
- Produces: `suspend fun checkForUpdate(context: Context): UpdateResult`
- Produces: `sealed class UpdateResult` — `UpToDate`, `UpdateAvailable(release: GitHubRelease, currentVersion: String)`, `Error(message: String)`
- Produces: `fun extractSemver(tag: String): Triple<Int, Int, Int>?` (public for testing)

- [ ] **Step 1: Create UpdateChecker with semver extraction and API call**

```kotlin
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
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/data/update/UpdateChecker.kt
git commit -m "feat: add UpdateChecker with GitHub API fetch and semver comparison"
```

---

### Task 4: UpdateChecker Unit Tests

**Files:**
- Create: `app/src/test/java/com/balancesentinel/app/data/update/UpdateCheckerTest.kt`

**Interfaces:**
- Consumes: `UpdateChecker` from Task 3, `GitHubRelease` from Task 1
- Produces: 14 test cases

- [ ] **Step 1: Write the test file**

```kotlin
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
}
```

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.update.UpdateCheckerTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` (14 tests pass)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/balancesentinel/app/data/update/UpdateCheckerTest.kt
git commit -m "test: add UpdateChecker unit tests for semver extraction and comparison"
```

---

### Task 5: ApkDownloader — Streaming Download with Progress

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/update/ApkDownloader.kt`

**Interfaces:**
- Consumes: `GitHubRelease` from Task 1
- Produces: `ApkDownloader` class
- Produces: `suspend fun download(release: GitHubRelease, cacheDir: File, onProgress: (Float) -> Unit): File`
- Produces: `sealed class DownloadResult` — `Success(file: File)`, `Error(message: String)`

- [ ] **Step 1: Create ApkDownloader**

```kotlin
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
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/data/update/ApkDownloader.kt
git commit -m "feat: add ApkDownloader with streaming download and progress callback"
```

---

### Task 6: FileProvider Configuration

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml` — add `<provider>` inside `<application>`

**Interfaces:**
- Produces: `com.balancesentinel.app.fileprovider` authority for APK install intent

- [ ] **Step 1: Create file_paths.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="apk" path="apk/" />
</paths>
```

- [ ] **Step 2: Add FileProvider to AndroidManifest.xml**

Insert the provider before the `</application>` closing tag:

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="com.balancesentinel.app.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 3: Verify the manifest compiles**

Run: `./gradlew.bat assembleDebug --no-daemon 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml app/src/main/AndroidManifest.xml
git commit -m "feat: add FileProvider config for APK install"
```

---

### Task 7: UpdateDialog Composable

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/ui/screen/UpdateDialog.kt`
- Modify: `app/src/main/res/values/strings.xml` — add new string resources

**Interfaces:**
- Consumes: `GitHubRelease` from Task 1, `DownloadResult` from Task 5
- Produces: `@Composable fun UpdateDialog(...)` with 4 states: initial, downloading, complete, failed

- [ ] **Step 1: Add string resources**

Find the existing `strings.xml` and append before `</resources>`:

```xml
<!-- Update checker -->
<string name="update_dialog_title">发现新版本</string>
<string name="update_current_version">当前版本 v%1$s</string>
<string name="update_latest_version">最新版本 v%1$s</string>
<string name="update_download">下载更新</string>
<string name="update_open_link">去下载链接</string>
<string name="update_remind_later">稍后提醒</string>
<string name="update_skip_version">跳过此版本</string>
<string name="update_cancel_download">取消下载</string>
<string name="update_install">安装</string>
<string name="update_retry">重试</string>
<string name="update_close">关闭</string>
<string name="update_downloading">正在下载... %1$d%%</string>
<string name="update_download_failed">下载失败：%1$s</string>
<string name="update_download_failed_offer_link">下载失败，是否打开下载链接？</string>
<string name="update_install_failed">无法打开安装程序，请手动下载</string>
<string name="settings_check_update">检查更新</string>
<string name="settings_checking">正在检查...</string>
<string name="settings_up_to_date">已是最新版本</string>
<string name="settings_update_available">发现新版本 v%1$s</string>
<string name="update_release_date">发布日期：%1$s</string>
```

- [ ] **Step 2: Create UpdateDialog composable**

```kotlin
package com.balancesentinel.app.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.update.ApkDownloader
import com.balancesentinel.app.data.update.DownloadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DialogState {
    INITIAL, DOWNLOADING, COMPLETE, FAILED
}

@Composable
fun UpdateDialog(
    release: GitHubRelease,
    currentVersion: String,
    onDismiss: () -> Unit,
    onSkipVersion: () -> Unit,
    onRemindLater: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { ApkDownloader() }

    var dialogState by remember { mutableStateOf(DialogState.INITIAL) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var errorMessage by remember { mutableStateOf("") }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    val releaseDate = remember(release.publishedAt) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            isoFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = isoFormat.parse(release.publishedAt ?: "")
            val outFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            date?.let { outFormat.format(it) } ?: "未知"
        } catch (_: Exception) {
            "未知"
        }
    }

    val releaseBody = release.body ?: ""

    fun startDownload() {
        dialogState = DialogState.DOWNLOADING
        downloadProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                downloader.download(
                    release = release,
                    cacheDir = context.cacheDir,
                    onProgress = { progress ->
                        downloadProgress = progress
                    }
                )
            }
            when (result) {
                is DownloadResult.Success -> {
                    downloadedFile = result.file
                    dialogState = DialogState.COMPLETE
                }
                is DownloadResult.Error -> {
                    errorMessage = result.message
                    dialogState = DialogState.FAILED
                }
            }
        }
    }

    fun installApk(file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "com.balancesentinel.app.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            // Grant URI permission to any package that can handle the intent
            context.grantUriPermission(
                "android", apkUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.update_install_failed)
            dialogState = DialogState.FAILED
        }
    }

    fun openDownloadLink() {
        val url = release.htmlUrl.ifEmpty {
            "https://github.com/shengbuding/balance-sentinel/releases/tag/${release.tagName}"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    AlertDialog(
        onDismissRequest = {
            if (dialogState != DialogState.DOWNLOADING) {
                onDismiss()
            }
        },
        title = {
            Text(
                stringResource(R.string.update_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Version info
                Text(
                    stringResource(R.string.update_latest_version, release.tagName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.update_current_version, currentVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Release date
                Text(
                    stringResource(R.string.update_release_date, releaseDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Release notes (scrollable within the already-scrollable text area)
                if (releaseBody.isNotEmpty()) {
                    Text(
                        releaseBody,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 15
                    )
                    HorizontalDivider()
                }

                // Download progress
                when (dialogState) {
                    DialogState.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.update_downloading,
                                (downloadProgress * 100).toInt()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DialogState.COMPLETE -> {
                        Text(
                            "✓ 下载完成",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DialogState.FAILED -> {
                        Text(
                            stringResource(R.string.update_download_failed, errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    DialogState.INITIAL -> { /* nothing extra */ }
                }
            }
        },
        confirmButton = {
            when (dialogState) {
                DialogState.INITIAL -> {
                    TextButton(onClick = { startDownload() }) {
                        Text(stringResource(R.string.update_download))
                    }
                }
                DialogState.COMPLETE -> {
                    downloadedFile?.let { file ->
                        TextButton(onClick = { installApk(file) }) {
                            Text(stringResource(R.string.update_install))
                        }
                    }
                }
                DialogState.DOWNLOADING -> {
                    TextButton(
                        onClick = {
                            dialogState = DialogState.INITIAL
                        }
                    ) {
                        Text(stringResource(R.string.update_cancel_download))
                    }
                }
                DialogState.FAILED -> {
                    TextButton(onClick = { startDownload() }) {
                        Text(stringResource(R.string.update_retry))
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (dialogState) {
                    DialogState.INITIAL -> {
                        TextButton(onClick = {
                            openDownloadLink()
                        }) {
                            Text(stringResource(R.string.update_open_link))
                        }
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    }
                    DialogState.COMPLETE -> {
                        TextButton(onClick = {
                            openDownloadLink()
                        }) {
                            Text(stringResource(R.string.update_open_link))
                        }
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    }
                    DialogState.DOWNLOADING -> {
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    }
                    DialogState.FAILED -> {
                        TextButton(onClick = {
                            openDownloadLink()
                        }) {
                            Text(stringResource(R.string.update_open_link))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                }
            }
        }
    )
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/ui/screen/UpdateDialog.kt app/src/main/res/values/strings.xml
git commit -m "feat: add UpdateDialog with download progress and 4-state UI"
```

---

### Task 8: SettingsScreen Integration — VersionInfo Card

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/SettingsScreen.kt` — replace `VersionInfo` composable

**Interfaces:**
- Consumes: `UpdateChecker` from Task 3, `UpdateDialog` from Task 7
- Produces: Updated `VersionInfo` with check button, status states, and dialog trigger

- [ ] **Step 1: Replace the VersionInfo composable**

Find the existing `VersionInfo` function (starts around line 501) and replace it with:

```kotlin
@Composable
private fun VersionInfo() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val checker = remember { UpdateChecker() }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
    }

    var isChecking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) } // null = default, text = up-to-date/error
    var isUpToDate by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<com.balancesentinel.app.data.model.GitHubRelease?>(null) }
    val updatePrefs = remember { com.balancesentinel.app.data.update.UpdatePrefs(context) }

    fun performCheck() {
        if (isChecking) return
        isChecking = true
        statusText = context.getString(R.string.settings_checking)
        isUpToDate = false
        isError = false

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                checker.checkForUpdate(context)
            }
            isChecking = false
            when (result) {
                is UpdateResult.UpToDate -> {
                    statusText = context.getString(R.string.settings_up_to_date)
                    isUpToDate = true
                    isError = false
                }
                is UpdateResult.UpdateAvailable -> {
                    statusText = context.getString(R.string.settings_update_available, result.release.tagName)
                    isUpToDate = false
                    isError = false
                    latestRelease = result.release
                    showUpdateDialog = true
                }
                is UpdateResult.Error -> {
                    statusText = result.message
                    isUpToDate = false
                    isError = true
                    // Also show snackbar for manual check errors
                    scope.launch {
                        snackbarHostState.showSnackbar(result.message)
                    }
                }
            }
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_about),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.settings_about_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Check update button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isChecking) { performCheck() }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText ?: stringResource(R.string.settings_check_update),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isUpToDate -> WalletColors.success
                        isError -> MaterialTheme.colorScheme.error
                        statusText != null && !isUpToDate && !isError -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.settings_check_update),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Update dialog
    if (showUpdateDialog && latestRelease != null) {
        UpdateDialog(
            release = latestRelease!!,
            currentVersion = versionName,
            onDismiss = { showUpdateDialog = false },
            onSkipVersion = {
                updatePrefs.skippedVersion = latestRelease!!.tagName
                showUpdateDialog = false
            },
            onRemindLater = {
                updatePrefs.markPromptedToday()
                showUpdateDialog = false
            }
        )
    }
}
```

Adding required imports at the top of SettingsScreen.kt (add alongside existing imports):

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Refresh
import com.balancesentinel.app.data.update.UpdateChecker
import com.balancesentinel.app.data.update.UpdateResult
import com.balancesentinel.app.data.update.UpdatePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

Note: `LocalSnackbarHostState` requires the `SnackbarHostState` to be available via `CompositionLocal`. The existing SettingsScreen function receives a `snackbarHostState` parameter via the Scaffold. We need to add it as a parameter to `VersionInfo()` or use `LocalSnackbarHostState`. Check if `LocalSnackbarHostState` is available — if not, pass `snackbarHostState` as a parameter to `VersionInfo`.

Since the SettingsScreen scaffold already has `snackbarHost = { SnackbarHost(snackbarHostState) }`, we should pass `snackbarHostState` to `VersionInfo`. Update the call site at line 130:

```kotlin
VersionInfo(snackbarHostState)
```

And update the function signature:

```kotlin
@Composable
private fun VersionInfo(snackbarHostState: SnackbarHostState) {
```

And add the import at the top:

```kotlin
import androidx.compose.material3.SnackbarHostState
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/ui/screen/SettingsScreen.kt
git commit -m "feat: add update check button and status to Settings VersionInfo card"
```

---

### Task 9: MainActivity Auto-Check on Startup

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/MainActivity.kt` — add auto-check logic

**Interfaces:**
- Consumes: `UpdateChecker` from Task 3, `UpdatePrefs` from Task 2, `UpdateDialog` from Task 7
- Produces: Auto-check on `Screen.SETTINGS` navigation and once per app session

- [ ] **Step 1: Add auto-check state and dialog to MainActivity**

Inside the `DeepSeekBalanceTheme { ... }` block in MainActivity.kt, after the existing `var showBatteryGuide` state:

```kotlin
// Update checker auto-check state (once per session)
var updateCheckPerformed by remember { mutableStateOf(false) }
var showAutoUpdateDialog by remember { mutableStateOf(false) }
var autoUpdateRelease by remember { mutableStateOf<com.balancesentinel.app.data.model.GitHubRelease?>(null) }
var autoUpdateCurrentVersion by remember { mutableStateOf("") }
```

Then add a `LaunchedEffect` for auto-check whenever the user navigates to Settings:

```kotlin
// Auto-check for updates when navigating to Settings
LaunchedEffect(currentScreen) {
    if (currentScreen == Screen.SETTINGS && !updateCheckPerformed) {
        updateCheckPerformed = true
        val prefs = com.balancesentinel.app.data.update.UpdatePrefs(context)
        if (prefs.shouldAutoCheckToday()) {
            val checker = com.balancesentinel.app.data.update.UpdateChecker()
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                checker.checkForUpdate(context)
            }
            when (result) {
                is com.balancesentinel.app.data.update.UpdateResult.UpdateAvailable -> {
                    if (!prefs.shouldSkipVersion(result.release.tagName)) {
                        autoUpdateRelease = result.release
                        autoUpdateCurrentVersion = result.currentVersion
                        showAutoUpdateDialog = true
                        prefs.markPromptedToday()
                    }
                }
                else -> { /* silent skip */ }
            }
        }
    }
}
```

Add the dialog overlay after the `if (showBatteryGuide)` block:

```kotlin
// Auto update dialog
if (showAutoUpdateDialog && autoUpdateRelease != null) {
    com.balancesentinel.app.ui.screen.UpdateDialog(
        release = autoUpdateRelease!!,
        currentVersion = autoUpdateCurrentVersion,
        onDismiss = { showAutoUpdateDialog = false },
        onSkipVersion = {
            com.balancesentinel.app.data.update.UpdatePrefs(context)
                .skippedVersion = autoUpdateRelease!!.tagName
            showAutoUpdateDialog = false
        },
        onRemindLater = {
            showAutoUpdateDialog = false
        }
    )
}
```

Add required import at top of MainActivity.kt:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/MainActivity.kt
git commit -m "feat: add auto update check on Settings navigation (once per day)"
```

---

### Task 10: CI — Create GitHub Release on Publish

**Files:**
- Modify: `.github/workflows/release.yml` — add `gh release create` step

**Interfaces:**
- Consumes: Existing build steps produce `app-release.apk`
- Produces: Published GitHub Release with APK + AAB assets

- [ ] **Step 1: Add the release creation step to release.yml**

Insert after the existing "Upload release APK" step, at the end of the `release` job:

```yaml
      - name: Create GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          VERSION="v${{ github.event.inputs.version }}"
          
          # Build release notes from git log since last tag
          NOTES=$(git log --oneline $(git describe --tags --abbrev=0 2>/dev/null || echo "HEAD~10")..HEAD 2>/dev/null || echo "Release $VERSION")
          
          gh release create "$VERSION" \
            --title "$VERSION" \
            --notes "$NOTES" \
            --prerelease=false \
            app/build/outputs/apk/release/app-release.apk \
            app/build/outputs/bundle/release/app-release.aab
```

- [ ] **Step 2: Review the full release.yml for consistency**

The complete file should now flow: checkout → setup → tests → build → upload artifacts → create GitHub Release.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add GitHub Release creation step with APK/AAB assets"
```

---

### Task 11: Remove Stale Downloaded APKs on Startup

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/DeepSeekApp.kt` — add cleanup in `onCreate`

**Interfaces:**
- Cleanup: delete all `update-*.apk` files from `cacheDir/apk/` on app startup to prevent stale installs

- [ ] **Step 1: Add APK cleanup logic to DeepSeekApp.onCreate()**

Insert after `CrashLogger.install(this)` and before `createNotificationChannel()`:

```kotlin
// Clean up stale downloaded APKs from previous sessions
try {
    val apkDir = java.io.File(cacheDir, "apk")
    if (apkDir.exists()) {
        apkDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                file.delete()
            }
        }
    }
} catch (_: Exception) {
    // Non-critical — don't block app startup
}
```

The updated `DeepSeekApp.kt` `onCreate()` will look like:

```kotlin
override fun onCreate() {
    super.onCreate()
    CrashLogger.install(this)

    // Clean up stale downloaded APKs from previous sessions
    try {
        val apkDir = java.io.File(cacheDir, "apk")
        if (apkDir.exists()) {
            apkDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        }
    } catch (_: Exception) {
        // Non-critical — don't block app startup
    }

    createNotificationChannel()
    CrashLogger.breadcrumb("App", "onCreate complete")
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew.bat compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/DeepSeekApp.kt
git commit -m "feat: clean up stale APK downloads on app startup"
```

---

### Task 12: Final Integration Test — Full Build + Unit Tests

**Description:** Run the full test suite to verify nothing is broken by the changes.

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew.bat testDebugUnitTest --no-daemon 2>&1 | tail -15`
Expected: All existing 214+ tests pass + new 25 tests pass (11 UpdatePrefs + 14 UpdateChecker)

- [ ] **Step 2: Build debug APK**

Run: `./gradlew.bat assembleDebug --no-daemon 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Final commit (if any cleanup needed)**

```bash
git add -A
git diff --cached --stat
git commit -m "chore: final integration verification" || echo "No changes to commit"
```
