# Design Spec: Automatic Update Checker

Date: 2026-07-06 | Status: Draft

## 1. Overview

Add GitHub Releases-based update detection to Balance Sentinel. On app startup, automatically check for new stable releases once per day. Users can also manually trigger a check from the Settings screen. When a new version is found, show an in-app dialog with options to download, snooze, or skip that version.

## 2. Architecture

```
SettingsScreen (VersionInfo card + manual check button)
       │
MainActivity.onResume (auto-check trigger)
       │
       ▼
UpdateChecker ── OkHttp ──► GitHub Releases API
       │                        GET /repos/shengbuding/balance-sentinel/releases
       ▼
UpdateDialog (Composable)
       │
UpdatePrefs (SharedPreferences)
       ├── last_prompt_date: String ("2026-07-06")
       └── skipped_version: String ("1.1.0")
```

## 3. Files

### 3.1 New Files

| File | Responsibility |
|---|---|
| `data/update/UpdateChecker.kt` | GitHub API call, parse release list, semver comparison, error classification |
| `data/update/UpdatePrefs.kt` | Read/write `lastPromptDate` and `skippedVersion` via SharedPreferences |
| `data/update/ApkDownloader.kt` | Download APK from GitHub release asset URL, streaming with progress callback, save to cache dir |
| `data/model/GitHubRelease.kt` | `@Serializable data class` for GitHub Release JSON (includes asset download URL) |
| `ui/screen/UpdateDialog.kt` | Full-screen takeover dialog with version info, release notes, download progress, and action buttons |
| `res/xml/file_paths.xml` | FileProvider paths config (cache-path for APK) |

### 3.2 Modified Files

| File | Change |
|---|---|
| `ui/screen/SettingsScreen.kt` | `VersionInfo` card gains "检查更新" button + status text |
| `MainActivity.kt` | `onResume` calls auto-check logic via coroutine |
| `AndroidManifest.xml` | Add `<provider>` for FileProvider (APK install) |
| `.github/workflows/release.yml` | Add `gh release create` step to publish GitHub Release with APK asset |

## 4. Behavior

### 4.1 Auto-Check (on startup)

1. If `lastPromptDate == today` → skip (already prompted today)
2. If no network → silent skip
3. `GET https://api.github.com/repos/shengbuding/balance-sentinel/releases`
4. Filter `prerelease == false`, take first result
5. Compare `tag_name` (semver) with `PackageManager.versionName`
6. If `latestVersion > currentVersion && latestVersion != skippedVersion`:
   - Show `UpdateDialog`
   - Set `lastPromptDate = today`
7. Otherwise → silent skip

### 4.2 Manual Check (Settings button)

1. If no network → show "网络不可用，请检查连接" snackbar
2. `GET https://api.github.com/repos/shengbuding/balance-sentinel/releases` (10s timeout)
3. On error → differentiate and show snackbar:

| Error | Message (zh-CN) |
|---|---|
| Network timeout | "连接超时，请稍后重试" |
| HTTP 403 / rate limited | "GitHub API 请求过于频繁，请稍后重试" |
| HTTP 4xx other | "请求失败 (HTTP {code})" |
| HTTP 5xx | "GitHub 服务异常，请稍后重试" |
| Parse error | "版本信息解析失败" |
| Other exception | "检查失败：{message}" |

4. If already latest → snackbar "已是最新版本 (v{current})"
5. If new version found → show `UpdateDialog` (ignore `skippedVersion` and `lastPromptDate`)

### 4.3 UpdateDialog

Content:
- Title: "发现新版本"
- `v{latest}` (current: `v{current}`)
- Release date
- Release notes body (from GitHub API, scrollable, max-height)
- Download progress bar (visible when downloading)
- Buttons:

Dialog has two rows of buttons that change based on state:

**Initial state (未开始下载):**

| Button | Behavior |
|---|---|
| **下载更新** | Start downloading APK from GitHub release asset URL, show progress bar |
| **去下载链接** | `Intent(ACTION_VIEW)` → open GitHub Releases page in browser (fallback) |
| **稍后提醒** | Dismiss. `lastPromptDate = today`. Prompts again tomorrow. |
| **跳过此版本** | Dismiss. Set `skippedVersion = latestTag`. Never prompts for this version again (auto-check only). |

**Downloading state:**

| Element | Behavior |
|---|---|
| Progress bar + percentage | Show download progress (determinate) |
| **取消下载** | Cancel download, revert to initial state |
| **稍后提醒** / **跳过此版本** | Still available (don't block user) |

**Download complete:**

| Button | Behavior |
|---|---|
| **安装** | Trigger Android package installer via `FileProvider` + `Intent(ACTION_VIEW)` |
| **去下载链接** | Still available as fallback |
| **稍后提醒** / **跳过此版本** | Still available |

**Download failed:**

| Element | Behavior |
|---|---|
| Error message | "下载失败：{reason}" |
| **重试** | Retry download |
| **去下载链接** | "下载失败，是否打开下载链接？" → open browser |
| **关闭** | Dismiss dialog |

### 4.4 APK Download & Install

- Download APK from GitHub Release asset URL: `https://github.com/shengbuding/balance-sentinel/releases/download/v{tag}/app-release.apk`
- Save to app-private cache dir: `context.cacheDir/apk/update-{version}.apk` (no storage permission needed)
- Use OkHttp streaming download with progress callback
- Install: `FileProvider` serves APK → `Intent(ACTION_VIEW, package-archive)` → system installer
- FileProvider authority: `com.balancesentinel.app.fileprovider`
- New manifest entry: `<provider>` for FileProvider
- New resource: `res/xml/file_paths.xml` (cache-path only)

### 4.5 Version Card States (Settings)

The `VersionInfo` card in settings shows:
- Default: current version + "检查更新" clickable row
- Checking: spinner + "正在检查..."
- Up-to-date: "已是最新版本" (green text)
- Error: error detail text in amber/red (as classified in 4.2)
- Update available: "发现新版本 v{X.Y.Z}" highlighted row, tap to re-show dialog

## 5. CI Changes

The current `release.yml` only uploads build artifacts — no GitHub Release is created. Add a final step to publish a proper GitHub Release so the update checker API can discover it.

### 5.1 New step in release.yml

```yaml
- name: Create GitHub Release
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    VERSION="v${{ github.event.inputs.version }}"
    gh release create "$VERSION" \
      --title "$VERSION" \
      --notes "Release $VERSION" \
      --prerelease=false \
      app/build/outputs/apk/release/app-release.apk \
      app/build/outputs/bundle/release/app-release.aab
```

### 5.2 Release naming convention

- Tag: `v1.0.0` (matches `versionName` prefix the update checker extracts)
- Title: `v1.0.0`
- Assets: APK + AAB attached to the release
- `prerelease: false` — ensures update checker's filter works correctly

## 6. Data Model (App)

```kotlin
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

APK download URL: `assets.firstOrNull { it.name.endsWith(".apk") }?.downloadUrl`
Fallback URL (if no asset): `https://github.com/shengbuding/balance-sentinel/releases/download/v{tag}/app-release.apk`

## 7. Error Handling Matrix

| Scenario | Auto-Check | Manual Check |
|---|---|---|
| No network | Silent skip | Snackbar "网络不可用，请检查连接" |
| HTTP timeout (10s) | Silent skip | Snackbar "连接超时，请稍后重试" |
| HTTP 403 | Silent skip | Snackbar "GitHub API 请求过于频繁，请稍后重试" |
| HTTP 4xx | Silent skip | Snackbar "请求失败 (HTTP {code})" |
| HTTP 5xx | Silent skip | Snackbar "GitHub 服务异常，请稍后重试" |
| JSON parse error | Silent skip | Snackbar "版本信息解析失败" |
| No stable release found | Silent skip | Snackbar "暂无正式版本发布" |
| Already latest | Silent skip | Snackbar "已是最新版本 (v{X})" |

## 8. Version Comparison

Extract the `X.Y.Z` semver core from tag strings via regex `(\d+)\.(\d+)\.(\d+)`, ignoring all prefix/suffix characters. Compare major → minor → patch as integers.

### Extraction rules

| Input | Extracted |
|---|---|
| `v1.2.3` | `1.2.3` |
| `1.2.3` | `1.2.3` |
| `v1.0.0-3-gabc1234-dirty` | `1.0.0` |
| `v1.2.3-beta.1` | `1.2.3` |
| `release-2026` | `0.0.0` (no match → unparseable) |

### Comparison logic

1. Extract `(major, minor, patch)` from both `tagName` (GitHub) and `versionName` (local)
2. If either fails to extract → treat as `0.0.0` (unparseable)
3. Compare major → minor → patch numerically
4. `latest > current` → update available
5. `latest <= current` → up to date (includes dev builds ahead of latest release)

## 9. Non-Goals (YAGNI)

- No auto-download APK
- No background periodic check (startup only)
- No pre-release detection
- No GitHub auth token (public repo, 60 req/hr is sufficient)
- No delta/incremental update
- No notification-based alerts (dialog only)
