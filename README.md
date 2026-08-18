# 钱包哨兵 (Balance Sentinel)

[English](#english) | [中文](#中文)

多AI供应商余额监控 Android 应用 — 支持14个AI供应商、多账户后台自动刷新、桌面小组件、余额预警、趋势分析、控制台数据同步、中英双语界面。**配置和历史数据本地存储，请求仅发往用户配置的端点，零追踪。**

Multi-AI-provider balance monitoring Android app — supports 14 AI providers, multi-account background auto-refresh, desktop widgets, balance alerts, trend analysis, console data sync, bilingual Chinese/English UI. **Configuration and history stay local; requests go only to endpoints you configure; zero tracking.**

---

## 中文

### 功能

- **多供应商支持** — 支持14个AI供应商：DeepSeek、OpenAI、Anthropic、Gemini、Mistral、Cohere、通义千问、文心一言、智谱GLM、Moonshot、豆包、百川、模力方舟、自定义
- **多账户管理** — 支持多个API Key，每个账户独立配置
- **自定义余额查询脚本** — 自定义供应商支持配置JavaScript查询脚本、卡片展示字段和余额计算字段，兼容cc-switch格式，内置NewAPI预设
- **控制台数据同步** — 支持 DeepSeek 和 Xiaomi MiMo 官方控制台登录，同步账户余额、用量统计、趋势图表
- **自定义平台控制台** — 支持添加任意 AI 平台控制台，通过 WebView 登录官方控制台查看数据，登录状态永久保持
- **后台自动刷新** — 前台服务、WorkManager 与精确闹钟恢复监控协同保活，支持 1/5/10/15/30/60 分钟间隔
- **权限引导** — 首次使用和设置页均可检查通知、精确闹钟与电池优化授权状态
- **5 种桌面小组件** — RemoteViews 驱动，2×1 / 2×2 / 3×1 / 4×2 / 5×1 尺寸，可配单账户或总余额
- **余额预警** — 分账户分币种低余额预警，阈值可调，支持暂停 (snooze)
- **异动通知** — 检测余额变化（充值/消耗），实时推送
- **日内 + 日历天趋势** — 24h 滑动窗口 + 每日摘要，sparkline 图表 + 充值/消耗分析
- **通知栏钱包** — 自定义排序，总余额双币种显示
- **账户调试** — 显示完整API请求和响应信息，支持自定义脚本执行错误诊断
- **数据管理** — 本地导出/导入配置，历史数据导出
- **刷新健康监控** — 成功率仪表盘，连续失败自动降频保护
- **缓存层** — 智能缓存策略，减少API调用
- **本地用量追踪** — 为无余额API的供应商提供估算
- **中英双语** — 设置页面一键切换简体中文 / English，自动记忆语言偏好
- **隐私优先** — EncryptedSharedPreferences (AES-256)，无 Firebase/分析/广告 SDK
- **安全加固** — Rhino JS 沙箱（ClassShutter + sealObject）隔离自定义脚本执行环境

### 截图

> TODO: 添加应用截图到 `screenshots/` 目录

### 构建

#### 要求

- JDK 17 (Amazon Corretto 推荐)
- Android SDK 35 (build-tools 35.0.0)
- Gradle 8.11.1 (自动下载)

#### 命令

```bash
# Windows (Git Bash)
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10"
export ANDROID_HOME="$HOME/Android/Sdk"

# Debug 编译
./gradlew.bat assembleDebug --no-daemon

# Release 编译（需要签名配置，见 SIGNING.md）
./gradlew.bat assembleRelease --no-daemon

# 运行测试 (1,486 JVM tests)
./gradlew.bat testDebugUnitTest --no-daemon
```

#### 签名

Release 构建需要 `keystore.properties`（不提交到 git）：

```properties
storeFile=deepseek-balance.jks
storePassword=<密码>
keyAlias=deepseek
keyPassword=<密码>
```

详见 [SIGNING.md](SIGNING.md)。

### 安装

- **Release APK**：从 [Releases](https://github.com/shengbuding/balance-sentinel/releases) 下载最新版，直接安装
- **要求**：Android 15+ (SDK 35)

### 权限

| 权限 | 用途 |
|------|------|
| INTERNET | 调用 AI 供应商 API |
| FOREGROUND_SERVICE | 后台定时刷新 |
| POST_NOTIFICATIONS | 余额预警通知 |
| SCHEDULE_EXACT_ALARM | 恢复后台监控与常驻通知 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 引导用户放宽后台限制 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| WAKE_LOCK | 刷新期间防止 CPU 休眠 |

### 隐私

- API Key 使用 Android EncryptedSharedPreferences (AES-256) 加密存储
- 所有余额数据仅存本地，不上传任何远程服务
- 零第三方追踪 / 分析 / 广告 SDK
- 外部请求：各AI供应商API (HTTPS) + api.github.com (检查更新)
- 详见[隐私政策](PRIVACY_POLICY.md)和[数据安全审计](docs/audit/data-safety-audit.md)

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Widget | RemoteViews (5 尺寸) |
| 网络 | OkHttp + kotlinx.serialization |
| 存储 | Room + EncryptedSharedPreferences |
| 服务 | Foreground Service + WorkManager + 精确闹钟恢复 |
| 国际化 | Android LocaleManager (API 35+), 中英双语 |
| 测试 | JUnit 4 + MockK + Robolectric + MockWebServer |
| 构建 | Gradle 8.11 + Version Catalog |

### 版本

当前：**v1.5.0** (2026-08-18)

[变更日志](CHANGELOG.md) | [GitHub Release](https://github.com/shengbuding/balance-sentinel/releases)

[Claude Code 发布审查交接](docs/claude-code-review-handoff.md)

### 反馈

- [GitHub Issues](https://github.com/shengbuding/balance-sentinel/issues)
- QQ 群：1049954410

---

## English

### Features

- **Multi-Provider Support** — Supports 14 AI providers: DeepSeek, OpenAI, Anthropic, Gemini, Mistral, Cohere, Qwen, Wenxin, Zhipu, Moonshot, Doubao, Baichuan, Model Ark, Custom
- **Multi-Account Management** — Support multiple API Keys with independent per-account configuration
- **Custom Balance Query Script** — Custom providers support JavaScript query scripts, card display fields, and selectable balance fields, with cc-switch compatibility and a NewAPI preset
- **Console Data Sync** — Login to DeepSeek and Xiaomi MiMo official consoles to sync account balance, usage statistics, and trend charts
- **Custom Platform Console** — Add any AI platform console, login via WebView to view data, session persists permanently
- **Background Auto-Refresh** — Foreground service, WorkManager, and exact-alarm recovery work together at 1/5/10/15/30/60 minute intervals
- **Permission Guide** — First-run and Settings flows show notification, exact-alarm, and battery-optimization authorization status
- **5 Desktop Widget Sizes** — RemoteViews-driven, 2×1 / 2×2 / 3×1 / 4×2 / 5×1, configurable per-account or total balance
- **Balance Alerts** — Per-account per-currency low-balance alerts with adjustable thresholds and snooze support
- **Change Notifications** — Real-time push notifications for balance changes (top-up/consumption)
- **Intraday + Calendar Day Trends** — 24h sliding window + daily summaries with sparkline charts and consumption analysis
- **Notification Bar Wallet** — Custom sorting, dual-currency total balance display
- **Account Debugging** — View complete API request and response details, support custom script execution error diagnosis
- **Data Management** — Local config export/import, historical data export
- **Refresh Health Monitoring** — Success rate dashboard with automatic rate-limit protection on consecutive failures
- **Caching Layer** — Smart caching strategy to reduce API calls
- **Local Usage Tracking** — Provides estimates for providers without balance API
- **Bilingual UI** — One-tap switch between Simplified Chinese / English in Settings, language preference persists across restarts
- **Privacy First** — EncryptedSharedPreferences (AES-256), no Firebase/analytics/ad SDKs
- **Security Hardening** — Rhino JS sandbox (ClassShutter + sealObject) for isolated custom script execution

### Screenshots

> TODO: Add app screenshots to `screenshots/` directory

### Build

#### Requirements

- JDK 17 (Amazon Corretto recommended)
- Android SDK 35 (build-tools 35.0.0)
- Gradle 8.11.1 (auto-downloaded)

#### Commands

```bash
# Windows (Git Bash)
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10"
export ANDROID_HOME="$HOME/Android/Sdk"

# Debug build
./gradlew.bat assembleDebug --no-daemon

# Release build (requires signing config, see SIGNING.md)
./gradlew.bat assembleRelease --no-daemon

# Run tests (1,486 JVM tests)
./gradlew.bat testDebugUnitTest --no-daemon
```

#### Signing

Release builds require `keystore.properties` (not committed to git):

```properties
storeFile=deepseek-balance.jks
storePassword=<password>
keyAlias=deepseek
keyPassword=<password>
```

See [SIGNING.md](SIGNING.md) for details.

### Installation

- **Release APK**: Download the latest version from [Releases](https://github.com/shengbuding/balance-sentinel/releases) and install directly
- **Requires**: Android 15+ (SDK 35)

### Permissions

| Permission | Purpose |
|------|------|
| INTERNET | Query AI provider APIs |
| FOREGROUND_SERVICE | Background scheduled refresh |
| POST_NOTIFICATIONS | Balance alert notifications |
| SCHEDULE_EXACT_ALARM | Recover background monitoring and the persistent notification |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Guide the user to relax background restrictions |
| RECEIVE_BOOT_COMPLETED | Start on boot |
| WAKE_LOCK | Prevent CPU sleep during refresh |

### Privacy

- API Key stored with Android EncryptedSharedPreferences (AES-256)
- All balance data stored locally — never uploaded to any remote service
- Zero third-party tracking / analytics / advertising SDKs
- External requests: AI provider APIs (HTTPS) + api.github.com (update check)
- See [Privacy Policy](PRIVACY_POLICY.md) and [Data Security Audit](docs/audit/data-safety-audit.md)

### Tech Stack

| Category | Technology |
|------|------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Widget | RemoteViews (5 sizes) |
| Network | OkHttp + kotlinx.serialization |
| Storage | Room + EncryptedSharedPreferences |
| Services | Foreground Service + WorkManager + exact-alarm recovery |
| i18n | Android LocaleManager (API 35+), Chinese/English bilingual |
| Testing | JUnit 4 + MockK + Robolectric + MockWebServer + AndroidX Compose Test + Kover |
| Build | Gradle 8.11 + Version Catalog |

### Version

Current: **v1.5.0** (2026-08-18)

[Changelog](CHANGELOG.md) | [GitHub Release](https://github.com/shengbuding/balance-sentinel/releases)

[Claude Code release review handoff](docs/claude-code-review-handoff.md)

### Feedback

- [GitHub Issues](https://github.com/shengbuding/balance-sentinel/issues)
- QQ Group: 1049954410

---

## Architecture

```
UI Layer   Screen / ViewModel              ← Compose, 12 screens + Onboarding
           ConsoleScreen                   ← Unified console (login + dashboard)
           ConsoleSelectScreen             ← Platform selector
           AddCustomPlatformScreen         ← Custom platform configuration
           AddAccountDialog                ← Multi-provider account creation
           AccountBalanceCard              ← Provider icons + edit/delete menu
Data Layer AiProvider (interface)           ← Provider abstraction
           ProviderFactory                 ← Factory pattern for provider creation
           DeepSeekProvider                ← DeepSeek implementation (real balance API)
           OpenAiCompatibleProvider        ← Generic OpenAI-compatible implementation
           ConsoleAuthProvider             ← Console auth interface
           AbstractConsoleAuth             ← Base console auth
           CustomConsoleAuth               ← Custom platform auth
           ConsoleSessionStore             ← Encrypted session storage
           CustomPlatformStore             ← Custom platform configuration storage
           SessionValidator                ← Session validation via network
           ProviderCache                   ← Caching layer (memory + SharedPreferences)
           ProviderHealthChecker           ← API health monitoring
           LocalUsageTracker               ← Local usage tracking for providers without balance API
           IntradayEngine                  ← 24h sliding window, per-pair top-up/grant/consumption
           DailyEngine                     ← Calendar day, RecordAggregator + per-pair detection
           ServiceHealthTracker            ← Refresh health: ≥3 consecutive failures alert, ≥10 protection mode (60-min interval)
           CleanupScheduler                ← Midnight + boot (aggregate → gap-fill → delete)
Storage    RawRecordStore                  ← ≥24h raw records, precise deletion
           DailySummaryStore               ← Auto gap-fill, no gaps
           UsageDataStore                  ← Usage snapshots (30 days / 90 records)
           RefreshLogStore                 ← Refresh logs
           RefreshStatsStore               ← Local refresh success-rate ring buffer (last 100)
           WidgetPrefs                     ← Global settings + alert toggles + notification bar wallet + language preference
           ApiKeyManager                   ← Encrypted API Key storage (supports multiple providers)
           ConfigManager                   ← Config export / import
           BalanceWidgetDataStore          ← Widget balance cache + aggregation
           WidgetConfigStore               ← Per-widget instance configuration
           DataExporter                    ← Historical data export
System     BalanceRefreshService           ← Foreground service + health tracking + protection mode + multi-provider refresh
           NotificationHelper              ← Alert / change / foreground / summary notifications
           BootReceiver                    ← Auto-start + keep-alive
           5 Widget providers              ← RemoteViews (2×1 / 2×2 / 3×1 / 4×2 / 5×1)
```

## Hardened Data Flow

- `RefreshCoordinator` owns refresh concurrency, while `AccountBalanceRefresher` performs provider work and `RefreshResultCommitter` is the only success-commit boundary. Manual, service, and widget entry points share this flow so stale or failed work cannot update cache, history, or alerts.
- `AccountLifecycleManager` centralizes account add/update/delete migration across credentials, history, summaries, widget state, alerts, and Console data.
- `BalanceQueryService`, `ScriptNetworkPolicy`, and `RhinoScriptRunner` keep native provider contracts separate from custom scripts and enforce HTTPS destination, redirect, DNS/private-address, and sandbox policy.
- `ConsoleOriginPolicy` and `ConsoleSessionCleaner` constrain WebView injection to exact authorized origins and clear session state on logout.
- `BackupImportPlanner` requires preview before apply; destructive replace requires complete credentials and two confirmations.
- `DebugCapturePolicy` gates diagnostic request/response capture by the debuggable build flag; Release packaging tests verify the capture path is inactive.

The v1.5.0 release baseline is 1,486 Debug and 1,486 Release JVM tests (3 skipped per variant), clean Debug/Release lint, and a passing Kover verification gate. The targeted `MainActivityTest` instrumentation smoke test passed 4/4; full connected-test discovery still has an emulator startup limitation recorded in the [Claude Code review handoff](docs/claude-code-review-handoff.md). Target-API device gaps are also tracked in [TEST_REPORT.md](TEST_REPORT.md).

## Navigation

```
ONBOARDING → HOME → INSIGHTS → CONSOLE_SELECT → CONSOLE → ADD_CUSTOM_PLATFORM → SETTINGS → ALERT_SETTINGS → LOG → DATA_MANAGEMENT
```

| Screen | Purpose |
|------|------|
| HOME | Multi-account balance cards + manual refresh |
| INSIGHTS | Intraday / calendar day trends + sparkline + top-up / consumption analysis |
| CONSOLE_SELECT | Platform console selector → DeepSeek / MiMo / Custom platforms |
| CONSOLE | WebView console (login + dashboard) with session validation |
| ADD_CUSTOM_PLATFORM | Configure custom AI platform console |
| SETTINGS | Refresh interval + alert entry + notification bar + data management + refresh stats + language switch + feedback |
| ALERT_SETTINGS | Per-account per-currency alert toggles + thresholds + snooze duration |
| LOG | Refresh logs + crash logs |
| DATA_MANAGEMENT | Data export / import + cache cleanup |

## Project Structure

```
DeepSeekBalance/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/balancesentinel/app/
│       │   │   ├── MainActivity.kt
│       │   │   ├── CrashLogger.kt
│       │   │   ├── data/
│       │   │   │   ├── api/
│       │   │   │   │   ├── AiProvider.kt                    # Provider interface
│       │   │   │   │   ├── ProviderType.kt                  # Provider type enum
│       │   │   │   │   ├── ProviderResult.kt                # Unified error handling
│       │   │   │   │   ├── UnifiedModels.kt                 # Unified data models
│       │   │   │   │   ├── ProviderFactory.kt               # Factory pattern
│       │   │   │   │   ├── DeepSeekApiService.kt            # Legacy DeepSeek service
│       │   │   │   │   ├── providers/
│       │   │   │   │   │   ├── DeepSeekProvider.kt          # DeepSeek implementation
│       │   │   │   │   │   ├── OpenAiCompatibleProvider.kt  # Generic implementation
│       │   │   │   │   │   └── ProviderConfigs.kt           # Provider configurations
│       │   │   │   │   ├── cache/
│       │   │   │   │   │   └── ProviderCache.kt             # Caching layer
│       │   │   │   │   ├── health/
│       │   │   │   │   │   └── ProviderHealthChecker.kt     # Health monitoring
│       │   │   │   │   └── tracking/
│       │   │   │   │       └── LocalUsageTracker.kt         # Local usage tracking
│       │   │   │   ├── console/
│       │   │   │   │   ├── auth/
│       │   │   │   │   │   ├── AuthModels.kt                # Session data model (supports permanent sessions)
│       │   │   │   │   │   ├── ConsoleAuthProvider.kt       # Auth interface
│       │   │   │   │   │   ├── AbstractConsoleAuth.kt       # Base auth implementation
│       │   │   │   │   │   ├── DeepSeekConsoleAuth.kt       # DeepSeek auth
│       │   │   │   │   │   ├── MimoConsoleAuth.kt           # MiMo auth
│       │   │   │   │   │   ├── CustomConsoleAuth.kt         # Custom platform auth
│       │   │   │   │   │   └── SessionValidator.kt          # Session validation via network
│       │   │   │   │   ├── store/
│       │   │   │   │   │   ├── ConsoleSessionStore.kt       # Encrypted session storage
│       │   │   │   │   │   └── CustomPlatformStore.kt       # Custom platform configuration storage
│       │   │   │   │   └── DebugLogger.kt                   # Debug-only logging
│       │   │   │   ├── engine/{Daily,Intraday}Engine.kt + ServiceHealthTracker.kt
│       │   │   │   ├── model/{BalanceResponse,DailySummary,RefreshLogEntry,...}.kt
│       │   │   │   ├── repository/{RawRecordStore,DailySummaryStore,UsageDataStore,...}.kt
│       │   │   │   ├── util/Logger.kt
│       │   │   │   └── update/{UpdateChecker,UpdatePrefs,ApkDownloader}.kt
│       │   │   ├── service/BalanceRefreshService.kt
│       │   │   ├── ui/
│       │   │   │   ├── screen/{Home,Insights,Settings,AlertSettings,Log,DataManagement,UpdateDialog}.kt
│       │   │   │   ├── viewmodel/{Home,Insights,Log,DataManagement,Console,DeepSeekConsole,Mimo,CustomConsole}ViewModel.kt
│       │   │   │   ├── console/
│       │   │   │   │   ├── ConsoleComponents.kt             # Shared components (TopBar, WebView, DebugPanel)
│       │   │   │   │   ├── ConsoleScreen.kt                 # Unified console screen (login + dashboard)
│       │   │   │   │   ├── ConsoleSelectScreen.kt           # Platform selector
│       │   │   │   │   └── AddCustomPlatformScreen.kt       # Custom platform configuration
│       │   │   │   ├── components/
│       │   │   │   │   ├── AddAccountDialog.kt              # Multi-provider account creation
│       │   │   │   │   └── AccountBalanceCard.kt            # Provider-aware balance card
│       │   │   │   ├── icons/
│       │   │   │   │   └── ProviderIcons.kt                 # Provider icons
│       │   │   │   └── theme/Theme.kt
│       │   │   ├── util/{BatteryOptimizationHelper,FormatUtils,OnboardingHelper}.kt
│       │   │   └── widget/{StaticWidgetProvider,WidgetConfigStore,BalanceWidgetDataStore,...}.kt
│       │   └── res/
│       │       ├── values/{strings,themes}.xml
│       │       ├── values-en/strings.xml
│       │       └── xml/widget_*.xml
│       ├── test/     ← 86 shared JVM test files
│       ├── testDebug/ and testRelease/ ← 2 build-variant test files
│       └── androidTest/ ← 8 classes, 39 instrumentation source tests
├── docs/
│   ├── claude-code-review-handoff.md ← v1.5.0 release review brief
│   ├── desktop-sync-contract.md   ← Reserved desktop-sync security contract
│   ├── superpowers/specs/    ← Design & plan documents
│   ├── adr/                  ← Architecture Decision Records
│   └── audit/                ← Security audit
├── .github/workflows/{ci,release}.yml
├── build.gradle.kts
├── settings.gradle.kts
├── SIGNING.md
└── README.md
```
