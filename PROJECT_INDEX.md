# Project Index — DeepSeek Balance Sentinel

Generated: 2026-07-05

---

## 1. Overview

**DeepSeek Balance Sentinel** (钱包哨兵) — an Android app that monitors DeepSeek API credit balance via desktop widgets and in-app screens. Built with Kotlin + Jetpack Compose (Material 3) + Glance AppWidgets. Uses a dual-engine insight architecture (v2.0): `IntradayEngine` (24h per-pair tracking) + `DailyEngine` (long-term calendar-day tracking).

- **Package**: `com.example.deepseekbalance`
- **Min/Target SDK**: 35 (Android 15+)
- **JDK**: 17
- **Gradle**: 8.11
- **Architecture**: MVVM
- **Test count**: 195 (16 test classes, all passing)

---

## 2. Directory Map

```
C:\Users\Administrator\
├── CLAUDE.md                          # Skill routing: gstack + Superpowers decision tree
├── DeepSeekBalance/                   # ★ Main Android project
│   ├── README.md                      # Build instructions, API docs
│   ├── build.gradle.kts               # Root build (Kotlin 2.1, Android 8.11)
│   ├── settings.gradle.kts            # Settings
│   ├── gradle.properties              # Gradle props
│   ├── local.properties               # SDK path
│   ├── keystore.properties            # Release signing (gitignored)
│   ├── package.json                   # Node deps (sharp for icon conversion)
│   ├── scripts/
│   │   └── convert-rounded-icon.mjs   # Icon conversion script
│   ├── docs/superpowers/
│   │   ├── plans/2026-07-05-insights-rewrite.md      # Implementation plan
│   │   └── specs/2026-07-05-insights-rewrite-design.md # Design doc
│   └── app/
│       ├── build.gradle.kts           # App module build (Compose, OkHttp, etc.)
│       ├── proguard-rules.pro         # ProGuard rules
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── java/com/example/deepseekbalance/
│           │   └── res/               # Layouts, drawables, mipmaps, values, xml
│           ├── test/                  # Unit tests (16 classes, 195 total)
│           └── androidTest/           # Instrumented tests (1 class)
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

## 3. Source Code Map (DeepSeekBalance/app/src/main/java/com/example/deepseekbalance/)

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
| `BalanceResponse.kt` | DeepSeek API response model (`is_available`, `balance_infos`, `total_balance`, `topped_up_balance`) |
| `AccountInfo.kt` | Per-currency account info |
| `DailySummary.kt` | Daily aggregated summary row |
| `UsageRecord.kt` | Raw usage record (stored per-refresh) |
| `RefreshLogEntry.kt` | Log entry for refresh history |

#### API (`data/api/`)

| File | Purpose |
|---|---|
| `DeepSeekApiService.kt` | OkHttp client — `GET https://api.deepseek.com/user/balance` |

#### Engine — v2.0 Dual Engine (`data/engine/`)

| File | Purpose |
|---|---|
| `RecordAggregator.kt` | Aggregation primitives: partition + snapshot-diff across arbitrary windows |
| `IntradayEngine.kt` | Per-pair 24h sliding-window insight: burn rate, pace, ETA exhaustion, spike detection |
| `IntradayModels.kt` | Output types for IntradayEngine |
| `DailyEngine.kt` | Calendar-day aggregation: consumed/toppedUp per day, trends, cumulative drift |
| `DailyModels.kt` | Output types for DailyEngine |

#### Repository (`data/repository/`)

| File | Purpose |
|---|---|
| `ApiKeyManager.kt` | AES-256 EncryptedSharedPreferences API key storage |
| `BalanceRepository.kt` | Fetch balance + store snapshots, compute deltas |
| `RawRecordStore.kt` | Raw usage records CRUD (Room-like JSON file store) |
| `UsageDataStore.kt` | Processed usage data queries |
| `DailySummaryStore.kt` | Daily aggregated summary persistence |
| `RefreshLogStore.kt` | Refresh attempt + result logging |
| `RefreshScheduler.kt` | Periodic background refresh (WorkManager) |
| `MidnightScheduler.kt` | Daily midnight rollover trigger |
| `CleanupScheduler.kt` | Old data pruning |
| `AlertChecker.kt` | Balance threshold alerts |
| `NotificationHelper.kt` | Android notifications |
| `ConfigManager.kt` | App configuration settings |
| `DataExporter.kt` | CSV/JSON data export |
| `LogExporter.kt` | Log export |
| `WidgetPrefs.kt` | Widget configuration preferences |

### 3.3 UI Layer

#### Screens (`ui/screen/`)

| File | Purpose |
|---|---|
| `HomeScreen.kt` | Main balance display + refresh |
| `InsightsScreen.kt` | v2.0 dual-engine insight cards |
| `SettingsScreen.kt` | API key, refresh interval, alerts config |
| `LogScreen.kt` | Refresh history viewer |
| `DataManagementScreen.kt` | Export/cleanup/import tools |

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

## 4. Test Map (16 classes, 195 tests)

### Engine Tests
| File | What it covers |
|---|---|
| `RecordAggregatorTest.kt` | Partition + snapshot-diff primitives |
| `IntradayEngineTest.kt` | Per-pair 24h burn rate, pace, spike detection |
| `DailyEngineTest.kt` | Calendar-day consumed/toppedUp, trends, cumulative drift |

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
| `AlertCheckerTest.kt` | Threshold alert logic |
| `WidgetPrefsTest.kt` | Widget config storage |

### Model Tests
| File | What it covers |
|---|---|
| `BalanceResponseTest.kt` | JSON deserialization of API response |

### ViewModel Tests
| File | What it covers |
|---|---|
| `InsightsViewModelTest.kt` | Dual-engine insight VM |
| `LogViewModelTest.kt` | Log screen VM |
| `DataManagementViewModelTest.kt` | Export/cleanup VM |

### Instrumented Tests
| File | What it covers |
|---|---|
| `HomeScreenTest.kt` | Compose UI test for home screen |

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

3. **Widget Architecture**: Glance AppWidget with 5 size variants (2×1 through 5×1), light/dark/compact variants. Data flows via `WidgetPrefs` + `BalanceRefreshService`.

4. **Security**: AES-256 EncryptedSharedPreferences for API key storage, OkHttp with Bearer token auth.

5. **Background Work**: WorkManager for periodic refresh + boot-triggered reschedule. Foreground service for widget updates.

6. **Skill Routing**: CLAUDE.md decision tree routes user requests through Superpowers (creative/debug/TDD) → gstack (review/QA/deploy) pipeline.

---

## 7. Claude Code Configuration

- **Model backend**: DeepSeek API (Anthropic-compatible endpoint) — `deepseek-v4-pro` for all tiers
- **Hooks**: PreToolUse gate on Grep/Glob (codebase memory discovery); SessionStart reminders on start/resume/clear/compact
- **Skills active**: brainstorming, find-skills, frontend-design, gstack, requesting-code-review, systematic-debugging, TDD, ui-ux-pro-max, using-superpowers, writing-plans, codebase-memory, skill-creator
- **Permission model**: Allowlisted Bash, WebSearch, WebFetch, Skill invocations

---

## 8. Key File Quick-Reference

| Need | File |
|---|---|
| Build the app | `DeepSeekBalance/` — `./gradlew assembleDebug` |
| Run all tests | `DeepSeekBalance/` — `./gradlew test` |
| API endpoint | `DeepSeekApiService.kt` — `GET /user/balance` |
| Insight logic | `IntradayEngine.kt` + `DailyEngine.kt` |
| Data storage | `RawRecordStore.kt`, `DailySummaryStore.kt`, `RefreshLogStore.kt` |
| Widget layouts | `res/layout/widget_balance*.xml` (5 variants) |
| Widget config | `res/xml/static_widget_info_*.xml` (5 sizes) |
| Theme | `Theme.kt` |
| Memory index | `.claude/projects/C--Users-Administrator/memory/MEMORY.md` |
| Skill routing | `CLAUDE.md` (project root) |
| Claude settings | `.claude/settings.json` + `.claude/settings.local.json` |
| Design doc | `docs/superpowers/specs/2026-07-05-insights-rewrite-design.md` |
