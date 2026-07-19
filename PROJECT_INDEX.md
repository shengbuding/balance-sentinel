# Project Index — DeepSeek Balance Sentinel / 项目索引 — 钱包哨兵

Generated / 生成日期: 2026-07-18

---

## 1. Overview / 概述

**Wallet Sentinel** (钱包哨兵) — an Android app that monitors AI provider API balance via desktop widgets and in-app screens. Supports 13 AI providers including DeepSeek, OpenAI, Anthropic and more. Built with Kotlin + Jetpack Compose (Material 3) + RemoteViews Widgets. Uses a dual-engine insight architecture (v2.0): `IntradayEngine` (24h per-pair tracking) + `DailyEngine` (long-term calendar-day tracking). Features foreground-service auto-refresh, multi-account balance alerts, bilingual Chinese/English UI, and a service health tracker with protection mode.

**钱包哨兵** — 一款通过桌面小组件和应用内屏幕监控多个 AI 供应商 API 余额的 Android 应用，支持 DeepSeek、OpenAI、Anthropic 等 13 个供应商。Kotlin + Jetpack Compose (Material 3) + RemoteViews Widgets 构建。双引擎洞察架构 (v2.0)：`IntradayEngine`（24h 逐对跟踪）+ `DailyEngine`（长期日历天跟踪）。支持前台服务自动刷新、多账户余额预警、中英双语界面和服务健康追踪保护模式。

- **Package / 包名**: `com.balancesentinel.app`
- **Min/Target SDK**: 35 (Android 15+)
- **JDK**: 17
- **Gradle**: 8.11
- **Architecture / 架构**: MVVM
- **Test count / 测试数**: 700+ unit tests (47 files), 34 instrumented tests (6 files), all passing / 全部通过
- **Release / 版本**: v1.2.1

---

## 2. Directory Map

```
C:\Users\Administrator\
├── CLAUDE.md                          # Skill routing: gstack + Superpowers decision tree
├── DeepSeekBalance/                   # ★ Main Android project
│   ├── README.md                      # Project overview, features, build guide
│   ├── PROJECT_INDEX.md               # This file — full project map
│   ├── PRODUCTION_AUDIT.md            # Production readiness audit (as of v1.0.0)
│   ├── PRIVACY_POLICY.md              # Privacy policy (ML)
│   ├── PLAY_CONSOLE_PERMISSIONS.md    # Play Console permission declarations
│   ├── PLAY_STORE_LISTING.md          # Play Store listing draft
│   ├── SIGNING.md                     # Release signing guide
│   ├── build.gradle.kts               # Root build (Kotlin 2.1, AGP 8.7.3)
│   ├── settings.gradle.kts            # Settings
│   ├── gradle.properties              # Gradle props
│   ├── local.properties               # SDK path
│   ├── keystore.properties            # Release signing (gitignored)
│   ├── package.json                   # Node deps (sharp for icon conversion)
│   ├── scripts/
│   │   └── convert-rounded-icon.mjs   # Icon conversion script
│   ├── docs/
│   │   ├── audit/data-safety-audit.md # Data safety audit report
│   │   └── superpowers/
│   │       ├── specs/2026-07-05-insights-rewrite-design.md     # v2.0 design
│   │       ├── specs/2026-07-05-launch-readiness-design.md     # Launch prep design
│   │       ├── plans/2026-07-05-insights-rewrite.md            # v2.0 implementation plan
│   │       ├── plans/2026-07-05-launch-readiness-plan.md       # Launch prep plan
│   │       ├── specs/2026-07-06-insights-consumption-chart-and-history-design.md
│   │       ├── specs/2026-07-06-update-checker-design.md
│   │       ├── plans/2026-07-06-insights-consumption-chart-and-history-plan.md
│   │       └── plans/2026-07-06-update-checker-plan.md
│   └── app/
│       ├── build.gradle.kts           # App module build (Compose, OkHttp, etc.)
│       ├── proguard-rules.pro         # ProGuard rules
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── java/com/balancesentinel/app/
│           │   └── res/               # Layouts, drawables, mipmaps, values, xml
│           ├── test/                  # Unit tests (22 classes, 254+ total)
│           └── androidTest/           # Instrumented tests (4 classes, 27 UI cases)
│
├── .claude/                           # Claude Code config + session data
│   ├── settings.json                  # Model config (deepseek-v4-pro), hooks
│   ├── settings.local.json            # Permissions allowlist
│   ├── agents/skill-creator.md        # Custom agent definition
│   ├── skills/                        # Active skills (symlinks to .agents/skills/)
│   ├── hooks/                         # CBM (codebase memory) hooks
│   ├── projects/C--Users-Administrator/
│   │   ├── memory/                    # ★ Persistent memory (9 files)
│   │   └── *.jsonl                    # Session transcripts
│   └── plugins/                       # Plugin marketplaces
│
├── .agents/                           # Agent + skill source files
│   ├── .skill-lock.json               # Skill version lock
│   └── skills/                        # Skill definitions
│
├── .gstack/                           # gstack skill suite data
│   └── projects/global-learnings.jsonl
│
└── .cc-connect/                       # cc-connect bridge config
```

---

## 3. Source Code Map (DeepSeekBalance/app/src/main/java/com/balancesentinel/app/)

### 3.1 Entry Points

| File | Purpose |
|---|---|
| `DeepSeekApp.kt` | Application class |
| `MainActivity.kt` | Single activity (Compose host) |
| `CrashLogger.kt` | Crash logging |

### 3.2 Data Layer

#### Model (`data/model/`)

| File | Purpose |
|---|---|
| `BalanceResponse.kt` | DeepSeek API response model (legacy, for backward compatibility) |
| `AccountInfo.kt` | Per-currency account info |
| `DailySummary.kt` | Daily aggregated summary row |
| `UsageRecord.kt` | Raw usage record (stored per-refresh) |
| `RefreshLogEntry.kt` | Log entry for refresh history |

#### API (`data/api/`)

| File | Purpose |
|---|---|
| `DeepSeekApiService.kt` | OkHttp client — `GET https://api.deepseek.com/user/balance` |

#### Update (`data/update/`)

| File | Purpose |
|---|---|
| `UpdateChecker.kt` | GitHub Releases API client for update detection |
| `UpdatePrefs.kt` | Update check preferences (last prompt date, skipped version) |
| `ApkDownloader.kt` | APK download with progress tracking |

#### Engine — v2.0 Dual Engine (`data/engine/`)

| File | Purpose |
|---|---|
| `RecordAggregator.kt` | Aggregation primitives: partition + snapshot-diff across arbitrary windows |
| `IntradayEngine.kt` | Per-pair 24h sliding-window insight: burn rate, pace, ETA exhaustion, spike detection |
| `IntradayModels.kt` | Output types for IntradayEngine |
| `DailyEngine.kt` | Calendar-day aggregation: consumed/toppedUp per day, trends, cumulative drift |
| `DailyModels.kt` | Output types for DailyEngine |
| `ServiceHealthTracker.kt` | Refresh health: consecutive failure tracking, alert threshold (≥3), protection mode (≥10 → 60-min interval) |

#### Repository (`data/repository/`)

| File | Purpose |
|---|---|
| `ApiKeyManager.kt` | AES-256 EncryptedSharedPreferences API key storage |
| `BalanceRepository.kt` | Fetch balance + store snapshots, compute deltas |
| `RawRecordStore.kt` | Raw usage records CRUD (SharedPreferences JSON store) |
| `UsageDataStore.kt` | Processed usage data queries |
| `DailySummaryStore.kt` | Daily aggregated summary persistence |
| `RefreshLogStore.kt` | Refresh attempt + result logging |
| `RefreshScheduler.kt` | Periodic background refresh scheduling + heartbeat |
| `RefreshStatsStore.kt` | Local refresh success-rate ring buffer (last 100) |
| `MidnightScheduler.kt` | Daily midnight rollover trigger |
| `CleanupScheduler.kt` | Old data pruning |
| `AlertChecker.kt` | Balance threshold + change alerts |
| `NotificationHelper.kt` | Android notifications (alert/change/foreground/group-summary) |
| `ConfigManager.kt` | App config export/import |
| `DataExporter.kt` | CSV/JSON data export |
| `LogExporter.kt` | Log export |
| `WidgetPrefs.kt` | Widget + notification + language preferences / 小组件 + 通知 + 语言偏好 |

### 3.3 UI Layer

#### Screens (`ui/screen/`)

| File | Purpose |
|---|---|
| `HomeScreen.kt` | Main balance display + multi-account cards + manual refresh |
| `InsightsScreen.kt` | v2.0 dual-engine insight cards, sparkline charts, per-pair analysis |
| `SettingsScreen.kt` | API key, refresh interval, widget config, notification bar, refresh stats dashboard, language switch, community links / API Key、刷新间隔、小组件、通知栏、统计仪表盘、语言切换、社区链接 |
| `AlertSettingsScreen.kt` | Per-account per-currency alert thresholds, change detection, snooze duration |
| `LogScreen.kt` | Refresh history + crash log viewer |
| `DataManagementScreen.kt` | Export/cleanup/import tools |
| `OnboardingScreen.kt` | First-run API key setup wizard |
| `BackupRestoreScreen.kt` | Config backup/restore UI |
| `ClearDataScreen.kt` | Selective data clearing (records, summaries, logs) |
| `UpdateDialog.kt` | In-app update dialog with download progress |

#### ViewModels (`ui/viewmodel/`)

| File | Purpose |
|---|---|
| `HomeViewModel.kt` | Balance fetch + refresh state |
| `InsightsViewModel.kt` | Dual-engine insight queries |
| `LogViewModel.kt` | Refresh log queries |
| `DataManagementViewModel.kt` | Export/cleanup operations |

#### Theme (`ui/theme/`)

| File | Purpose |
|---|---|
| `Theme.kt` | Material 3 theme |
| `CustomIcons.kt` | Custom icon composables |

### 3.4 Services & Receivers

| File | Purpose |
|---|---|
| `service/BalanceRefreshService.kt` | Foreground service for widget refresh |
| `receiver/BootReceiver.kt` | Reschedule refresh on boot |
| `receiver/MidnightReceiver.kt` | Daily summary rollover trigger |
| `receiver/KeepAliveReceiver.kt` | WakeLock-based keep-alive for foreground service |
| `receiver/SnoozeReceiver.kt` | Snooze notification actions |

### 3.5 Resources (`res/`)

| Category | Files |
|---|---|
| Layouts (5) | `widget_balance.xml`, `widget_balance_dark.xml`, `widget_balance_compact.xml`, `widget_balance_compact_dark.xml`, `widget_placeholder.xml` |
| Widget config (5) | `static_widget_info_2x1.xml` through `5x1.xml` — 5 widget sizes |
| Drawables (3) | `ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_refresh.xml` |
| Themes | `values/themes.xml`, `values-night/themes.xml` |
| Strings | `values/strings.xml`, `values-en/strings.xml` |
| Mipmaps | 10 launcher icon PNGs (5 densities × 2 variants) |

---

## 4. Test Map (47 unit + 6 instrumented, 700+ tests)

### Engine Tests
| File | What it covers |
|---|---|
| `RecordAggregatorTest.kt` | Partition + snapshot-diff primitives |
| `IntradayEngineTest.kt` | Per-pair 24h burn rate, pace, spike detection |
| `DailyEngineTest.kt` | Calendar-day consumed/toppedUp, trends, cumulative drift |

### API Tests
| File | What it covers |
|---|---|
| `DeepSeekApiServiceTest.kt` | OkHttp client, auth headers, JSON deserialization, error responses |

### Repository Tests
| File | What it covers |
|---|---|
| `BalanceRepositoryTest.kt` | Fetch + snapshot + delta computation |
| `RawRecordStoreTest.kt` | CRUD operations on raw records |
| `UsageDataStoreTest.kt` | Processed data queries |
| `DailySummaryStoreTest.kt` | Daily aggregation persistence |
| `RefreshLogStoreTest.kt` | Refresh logging CRUD |
| `RefreshSchedulerTest.kt` | Periodic refresh scheduling |
| `CleanupSchedulerTest.kt` | Old data pruning |
| `AlertCheckerTest.kt` | Threshold + change alert logic |
| `WidgetPrefsTest.kt` | Widget config storage |

### Model Tests
| File | What it covers |
|---|---|
| `BalanceResponseTest.kt` | JSON deserialization of API response |

### Widget Tests
| File | What it covers |
|---|---|
| `BalanceWidgetDataStoreTest.kt` | Widget balance cache + aggregation |
| `WidgetConfigStoreTest.kt` | Per-widget instance config persistence |
| `WidgetProviderTest.kt` | RemoteViews update + refresh logic |

### ViewModel Tests
| File | What it covers |
|---|---|
| `InsightsViewModelTest.kt` | Dual-engine insight VM |
| `LogViewModelTest.kt` | Log screen VM |
| `DataManagementViewModelTest.kt` | Export/cleanup VM |

### Instrumented Tests (androidTest)
| File | What it covers |
|---|---|
| `HomeScreenTest.kt` | Compose UI test for home screen |
| `OnboardingScreenTest.kt` | First-run API key setup flow |
| `SettingsScreenTest.kt` | Settings screen interactions |
| `InsightsScreenTest.kt` | Insights screen rendering |
| `ServiceTest.kt` | Service lifecycle + restart |
| `MainActivityTest.kt` | Activity lifecycle + navigation |

### Update Tests
| File | What it covers |
|---|---|
| `UpdateCheckerTest.kt` | GitHub Releases API + semver comparison |
| `UpdatePrefsTest.kt` | Update preference persistence |

---

## 5. Memory Index (`.claude/projects/C--Users-Administrator/memory/`)

| File | Summary |
|---|---|
| `MEMORY.md` | Index of all memories |
| `gstack-superpowers-integration.md` | gstack + Superpowers unified routing in CLAUDE.md |
| `deepseek-balance-android-widget.md` | v2.0 insights rewrite completed. 195 tests, dual-engine architecture |
| `balance-sentinel-v20-insights-rewrite.md` | Dual-engine architecture complete. 195 tests. Design docs archived |
| `insights-engine-architecture.md` | IntradayEngine (24h per-pair) + DailyEngine (calendar days). Pure Kotlin |
| `balance-sentinel-v16-phase4-next.md` | v1.6.5 five-phase completion (superseded by v2.0) |
| `balance-sentinel-bug-fixes.md` | Three critical data-pipeline bugs fixed: coin detection deadlock, cross-day top-up detection, RawRecordStore delete precision |
| `dailyengine-consumed-inconsistency.md` | consumed vs toppedUp accounting identity fix |
| `balance-sentinel-v20-bug-fixes.md` | Three v2.0 fixes: consumed/toppedUp inconsistency, Day1 trend label, alert anchor cumulative comparison |

---

## 6. Key Architecture Decisions

1. **Dual Engine (v2.0)**: `IntradayEngine` handles per-pair 24h rolling windows; `DailyEngine` aggregates by calendar day. No Android dependencies — pure Kotlin.

2. **RecordAggregator**: Shared primitive layer between both engines — partitions records by pair/window, computes snapshot diffs, delta accounting (consumed = opening - closing + toppedUp).

3. **Widget Architecture**: RemoteViews AppWidget with 5 size variants (2×1 through 5×1), light/dark/compact variants. Data flows via `WidgetConfigStore` + `BalanceWidgetDataStore` + `BalanceRefreshService` foreground refresh.

4. **Security**: AES-256 EncryptedSharedPreferences for API key storage, OkHttp with Bearer token auth.

5. **Background Work**: Handler + foreground service for periodic refresh with keep-alive. BootReceiver + MidnightReceiver for lifecycle rescheduling. No WorkManager dependency.

6. **Skill Routing**: CLAUDE.md decision tree routes user requests through Superpowers (creative/debug/TDD) → gstack (review/QA/deploy) pipeline.

---

## 7. Claude Code Configuration

- **Model backend**: DeepSeek API (Anthropic-compatible endpoint) — `deepseek-v4-pro` for all tiers
- **Hooks**: PreToolUse gate on Grep/Glob (codebase memory discovery); SessionStart reminders on start/resume/clear/compact
- **Skills active**: brainstorming, find-skills, frontend-design, gstack, receiving-code-review, diagnosing-bugs, TDD, ui-ux-pro-max, using-superpowers, writing-plans, codebase-memory, skill-creator
- **Permission model**: Allowlisted Bash, WebSearch, WebFetch, Skill invocations

---

## 8. Key File Quick-Reference

| Need | File |
|---|---|
| Build the app | `DeepSeekBalance/` — `./gradlew assembleDebug` |
| Run all tests | `DeepSeekBalance/` — `./gradlew test` |
| API endpoint | `DeepSeekApiService.kt` — `GET /user/balance` |
| Insight logic | `IntradayEngine.kt` + `DailyEngine.kt` |
| Health tracking | `ServiceHealthTracker.kt` |
| Data storage | `RawRecordStore.kt`, `DailySummaryStore.kt`, `RefreshLogStore.kt` |
| Widget layouts | `res/layout/widget_balance*.xml` (5 variants) |
| Widget config | `res/xml/static_widget_info_*.xml` (5 sizes) |
| Widget data | `BalanceWidgetDataStore.kt` + `WidgetConfigStore.kt` |
| Theme | `Theme.kt` |
| Signing | `SIGNING.md` |
| Privacy | `PRIVACY_POLICY.md` + `docs/audit/data-safety-audit.md` |
| Play Store | `PLAY_STORE_LISTING.md` + `PLAY_CONSOLE_PERMISSIONS.md` |
| Memory index | `.claude/projects/C--Users-Administrator/memory/MEMORY.md` |
| Skill routing | `CLAUDE.md` (project root) |
| Claude settings | `.claude/settings.json` + `.claude/settings.local.json` |
| Design docs | `docs/superpowers/specs/` |
