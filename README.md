# 钱包哨兵 (Balance Sentinel)

DeepSeek API 余额监控 Android 应用 — 多账户后台自动刷新、桌面小组件、余额预警、趋势分析。**数据 100% 本地存储，零追踪。**

## 功能

- **多账户管理** — 支持多个 DeepSeek API Key，每个账户独立配置
- **后台自动刷新** — 前台服务保活，Handler 定时轮询，支持 1/5/10/15/30/60 分钟间隔
- **5 种桌面小组件** — RemoteViews 驱动，2×1 / 2×2 / 3×1 / 4×2 / 5×1 尺寸，可配单账户或总余额
- **余额预警** — 分账户分币种低余额预警，阈值可调，支持暂停 (snooze)
- **异动通知** — 检测余额变化（充值/消耗），实时推送
- **日内 + 日历天趋势** — 24h 滑动窗口 + 每日摘要，sparkline 图表 + 充值/消耗分析
- **通知栏钱包** — 自定义排序，总余额双币种显示
- **数据管理** — 本地导出/导入配置，历史数据导出
- **刷新健康监控** — 成功率仪表盘，连续失败自动降频保护
- **隐私优先** — EncryptedSharedPreferences (AES-256)，无 Firebase/分析/广告 SDK，仅请求 api.deepseek.com

## 截图

> TODO: 添加应用截图到 `screenshots/` 目录

## 构建

### 要求

- JDK 17 (Amazon Corretto 推荐)
- Android SDK 35 (build-tools 35.0.0)
- Gradle 8.11 (自动下载)

### 命令

```bash
# Windows (Git Bash)
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10"
export ANDROID_HOME="$HOME/Android/Sdk"

# Debug 编译
./gradlew.bat assembleDebug --no-daemon

# Release 编译（需要签名配置，见 SIGNING.md）
./gradlew.bat assembleRelease --no-daemon

# 运行测试 (214+ unit + 26 UI)
./gradlew.bat testDebugUnitTest --no-daemon
```

### 签名

Release 构建需要 `keystore.properties`（不提交到 git）：

```properties
storeFile=../deepseek-balance.jks
storePassword=<密码>
keyAlias=deepseek
keyPassword=<密码>
```

详见 [SIGNING.md](SIGNING.md)。

## 安装

- **Release APK**：从 [Releases](https://github.com/shengbuding/balance-sentinel/releases) 下载最新版，直接安装
- **要求**：Android 15+ (SDK 35)

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Widget | RemoteViews (5 尺寸) |
| 网络 | OkHttp + kotlinx.serialization |
| 存储 | EncryptedSharedPreferences + SharedPreferences (JSON, 无 Room) |
| 服务 | Foreground Service + Handler 定时循环 |
| 测试 | JUnit 4 + MockK + Robolectric + MockWebServer |
| 构建 | Gradle 8.11 + Version Catalog |

## 架构

```
UI 层     Screen / ViewModel          ← Compose, 7 screens + Onboarding
数据层    IntradayEngine               ← 24h 滑动窗口, per-pair 充值/赠送/消耗
          DailyEngine                  ← 日历天, RecordAggregator + per-pair 检测
          ServiceHealthTracker         ← 刷新健康追踪：连续失败 ≥3 通知，≥10 保护模式降频
          CleanupScheduler             ← 午夜 + 启动 (聚合 → 补零 → 删除)
存储层    RawRecordStore               ← ≥24h raw records, 精确删除
          DailySummaryStore            ← 自动补零无缺口
          UsageDataStore               ← 用量快照 (30 天 / 90 条)
          RefreshLogStore              ← 刷新日志
          RefreshStatsStore            ← 本地刷新成功率环形缓冲区 (最近 100 次)
          WidgetPrefs                  ← 全局设置 + 预警开关 + 通知栏钱包选择
          ApiKeyManager                ← 加密存储 API Key
          ConfigManager                ← 配置导出 / 导入
          BalanceWidgetDataStore       ← Widget 余额缓存 + 聚合
          WidgetConfigStore            ← per-widget 实例配置
          DataExporter                 ← 历史数据导出
系统层    BalanceRefreshService        ← 前台服务保活 + 健康追踪 + 保护模式降频
          NotificationHelper           ← 预警 / 异动 / 前台 / 摘要通知
          BootReceiver                 ← 自启 + 保活
          5 Widget providers           ← RemoteViews (2×1 / 2×2 / 3×1 / 4×2 / 5×1)
```

## 导航

```
ONBOARDING → HOME → INSIGHTS → SETTINGS → ALERT_SETTINGS → LOG → DATA_MANAGEMENT
```

| 页面 | 职责 |
|------|------|
| HOME | 多账户余额卡片 + 手动刷新 |
| INSIGHTS | 日内 / 日历天趋势 + sparkline + 充值 / 消耗分析 |
| SETTINGS | 刷新间隔 + 预警入口 + 通知栏设置 + 数据管理入口 + 刷新统计仪表盘 + 交流反馈 |
| ALERT_SETTINGS | 分账户分币种预警开关 + 阈值 + 暂停时长 |
| LOG | 刷新日志 + 崩溃日志 |
| DATA_MANAGEMENT | 数据导出 / 导入 + 缓存清理 |

## 项目结构

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
│       │   │   │   ├── api/DeepSeekApiService.kt
│       │   │   │   ├── engine/{Daily,Intraday}Engine.kt + ServiceHealthTracker.kt
│       │   │   │   ├── model/{BalanceResponse,DailySummary,RefreshLogEntry,...}.kt
│       │   │   │   ├── repository/{RawRecordStore,DailySummaryStore,UsageDataStore,...}.kt
│       │   │   │   └── util/Logger.kt
│       │   │   ├── receiver/{Boot,KeepAlive,Midnight,Snooze}Receiver.kt
│       │   │   ├── service/BalanceRefreshService.kt
│       │   │   ├── ui/
│       │   │   │   ├── screen/{Home,Insights,Settings,AlertSettings,Log,DataManagement,...}Screen.kt
│       │   │   │   ├── viewmodel/{Home,Insights,Log,DataManagement}ViewModel.kt
│       │   │   │   └── theme/Theme.kt
│       │   │   ├── util/{BatteryOptimizationHelper,FormatUtils,OnboardingHelper}.kt
│       │   │   └── widget/{StaticWidgetProvider,WidgetConfigStore,BalanceWidgetDataStore,...}.kt
│       │   └── res/
│       │       ├── values/{strings,themes}.xml
│       │       ├── values-en/strings.xml
│       │       └── xml/widget_*.xml
│       └── test/     ← 20 个测试文件, 214+ unit + 26 UI 用例
├── docs/
│   └── audit/data-safety-audit.md
├── .github/workflows/{ci,release}.yml
├── build.gradle.kts
├── settings.gradle.kts
├── SIGNING.md
└── README.md
```

## 权限

| 权限 | 用途 |
|------|------|
| INTERNET | 调用 DeepSeek API |
| FOREGROUND_SERVICE | 后台定时刷新 |
| POST_NOTIFICATIONS | 余额预警通知 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| SCHEDULE_EXACT_ALARM | 精确定时调度 |
| WAKE_LOCK | 刷新期间防止 CPU 休眠 |

## 隐私

- API Key 使用 Android EncryptedSharedPreferences (AES-256) 加密存储
- 所有余额数据仅存本地，不上传任何远程服务
- 零第三方追踪 / 分析 / 广告 SDK
- 唯一外部请求：api.deepseek.com (HTTPS)
- 详见[隐私政策](PRIVACY_POLICY.md)和[数据安全审计](docs/audit/data-safety-audit.md)

## 版本

当前：**v1.0.0** (2026-07-05)

[Changelog](https://github.com/shengbuding/balance-sentinel/releases)

## 反馈

- [GitHub Issues](https://github.com/shengbuding/balance-sentinel/issues)
- QQ 群：1049954410
