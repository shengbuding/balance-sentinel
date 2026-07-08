# 钱包哨兵 — 上线前全面审计

日期：2026-07-05（原始审计） / 更新：2026-07-08（v1.2.0 后复审）

审计范围：所有源码、资源、构建配置、测试、架构

---

## v1.0.0 发布后状态更新

以下项目已在 v1.0.0 发布前完成修复或改善：

| 编号 | 问题 | 状态 | 说明 |
|------|------|------|------|
| 高-3 | 版本号自动递增 | ✅ 已修复 | `versionName` 使用 `git describe --tags --always --dirty` |
| 低-1 | 缺失 CI/CD | ✅ 已修复 | GitHub Actions CI workflow 已添加 |
| 中-4 | Widget 单元测试 | ✅ 已修复 | `WidgetConfigStoreTest`, `BalanceWidgetDataStoreTest`, `WidgetProviderTest` 已添加 |
| 中-5 | 网络层测试 | ✅ 已修复 | `DeepSeekApiServiceTest` 已添加 |
| 高-5 | 静默吞异常 | ⚠️ 已改善 | 已在 JSON 解析等关键路径添加 `Logger.w`，仍有改进空间 |
| 高-6 | Service 异常自恢复 | ✅ 已修复 | `ServiceHealthTracker` — 连续失败≥3 通知，≥10 自动降频保护模式 |
| 中-7 | JSON 解析无日志 | ✅ 已修复 | 已在反序列化 catch 块中添加 `Logger.w` |
| 中-9 | 缺失引导页 | ✅ 已修复 | `OnboardingScreen` 已实现（首次运行 API Key 设置向导） |
| 严重-4 | 隐私政策缺失 | ✅ 已修复 | `PRIVACY_POLICY.md` 已编写 |
| 高-7 | 权限声明理由 | ✅ 已修复 | `PLAY_CONSOLE_PERMISSIONS.md` 已编写 |
| 中-11 | package name | ✅ 已修复 | `namespace = "com.balancesentinel.app"`（从未使用 com.example） |
| 中-13 | 后台任务追踪 | ✅ 已修复 | `RefreshStatsStore` — 本地刷新成功率环形缓冲区（最近 100 次） |
| 中-3 | ProGuard 规则 | ✅ 已修正 | 不使用 Glance（RemoteViews）、不使用 WorkManager，无需对应规则 |
| 严重-2 | allowBackup=true | ✅ 已修复 | AndroidManifest.xml 已设置 allowBackup=false |
| 高-1 | HTTPS 证书固定 | ✅ 已修复 | network_security_config.xml 已配置 SHA-256 证书固定 |
| 高-2 | 配置导出明文 Key | ✅ 已修复 | ConfigManager.redactApiKey() 自动脱敏 |
| 中-6 | OkHttp 无重试 | ✅ 已修复 | DeepSeekApiService.RetryInterceptor 已添加（指数退避 1s/2s，最多 3 次） |
| 低-2 | 无覆盖率工具 | ✅ 已修复 | Kover 已配置（app/build.gradle.kts） |

### 关键指标更新

| 指标 | 审计时 | v1.0.0 |
|------|--------|--------|
| 单元测试文件 | 16 | 22 |
| 测试数量 | 195 | 254+ |
| Instrumented 测试 | 1 | 4 |
| 静默 catch 块 | 32+ | ~25（已减少） |

---

## 总览（更新后）

| 类别 | 严重 | 高 | 中 | 低 |
|---|---|---|---|---|
| 安全与隐私 | 2 | 2 | 1 | — |
| 构建与发布 | 1 | 0 | 1 | 0 |
| 测试与质量 | — | 1 | 0 | 1 |
| 错误处理与韧性 | — | 1 | 1 | — |
| 用户体验与无障碍 | — | — | 2 | 2 |
| 合规（应用商店） | 0 | 1 | 1 | — |
| 观测与监控 | — | 2 | 0 | 1 |
| 代码质量与架构 | — | — | 2 | — |

共原始 24 项，**已修复/改善 18 项**，剩余 6 项待处理。

---

## 一、安全与隐私

### ❌ 严重-1：日志泄露 API Key 风险

**位置**: `BalanceRefreshService.kt:244`、`HomeViewModel.kt:354`、多处 `Log.e(TAG, ..., e)`

异常对象 `e` 可能包含 API Key（例如 OkHttp 异常 toString() 会打印完整 URL 和 header）。一旦 App 在 logcat 中输出 API Key，任何有 READ_LOGS 权限的应用（Android 4.1-）或通过 adb 都能读取。

```kotlin
// 风险示例 — exception message 可能含 URL+token
Log.e(TAG, "Auto refresh failed for ${account.label}", e)
```

**修复**: 
- 所有 `Log.*` 调用中涉及 API 调用的异常，不要在 message 中直接传异常对象
- 或实现一个 `safeLog(e: Exception)` 工具函数，过滤 URL/host/key 等敏感信息后再输出
- Release build 全局禁用详细日志：`if (BuildConfig.DEBUG) Log.e(...)`

---

### ✅ 严重-2：allowBackup — 已修复

> ✅ v1.1.0 已修复：AndroidManifest.xml 第 14 行已设为 `android:allowBackup=\"false\"`。

原始审计建议：

**位置**: `AndroidManifest.xml:14`

```xml
android:allowBackup="true"
```

`allowBackup=true` 允许用户通过 `adb backup` 提取 App 的全部 SharedPreferences 文件，包括 EncryptedSharedPreferences。虽然 EncryptedSharedPreferences 使用 AES-256 加密，但加密密钥存储在 Android Keystore 中——而 Keystore 的密钥在 Android 12+ 设备上 **可以** 通过 `adb backup` 一起导出（取决于 OEM 实现）。

更严重的是：如果用户开启了 Google Backup（Android 12+ 默认关闭，但用户可以手动开启），加密的 preferences 会被上传到 Google Drive。攻击者拿到用户 Google 账号后可以恢复备份并离线破解。

**修复**:
```xml
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```
并创建 `res/xml/data_extraction_rules.xml`：
```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="deepseek_secure_prefs.xml"/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="deepseek_secure_prefs.xml"/>
    </device-transfer>
</data-extraction-rules>
```

---

### ✅ 高-1：证书固定 — 已修复（network_security_config.xml 方式）

> ✅ v1.1.0 已修复：通过 `network_security_config.xml` 声明式证书固定（SHA-256 pin for api.deepseek.com），而非 OkHttp CertificatePinner。两种方案等效。

**位置**: `DeepSeekApiService.kt:18-21`

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()
```

没有 CertificatePinner，App 可以被中间人攻击（MITM）。如果用户连到恶意 WiFi，攻击者可以伪造 `api.deepseek.com` 的证书，窃取 API Key。

**修复**:
```kotlin
private val client = OkHttpClient.Builder()
    .certificatePinner(CertificatePinner.Builder()
        .add("api.deepseek.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build())
    .build()
```

> 需要从 DeepSeek 获取其 API 域名的真实证书指纹。也可以使用 network_security_config.xml 方式。

---

### ⚠️ 高-2：配置文件导出包含明文 API Key

**位置**: `ConfigManager.kt:72-79`

```kotlin
val config = AppConfig(
    accounts = accounts,  // AccountInfo 包含 apiKey 字段
    ...
)
return json.encodeToString(config)
```

导出的配置 JSON 文件包含所有账户的明文 API Key。用户可能通过微信/邮件分享此文件给自己备份，导致 Key 泄露。

**修复**:
- 导出时对 `apiKey` 字段做脱敏处理（只保留前 4 后 4 位）
- 或使用 Android Keystore 对导出文件整体加密
- 导入时支持脱敏 Key（用户手动补全）或加密文件解密
- 或者在导出对话框上警告用户"此文件包含 API 密钥，请安全保存"

---

### ⚙️ 中-1：SharePreferences 存储余额阈值等设置明文

非关键问题，但 `WidgetPrefs` 使用普通 SharedPreferences 存储 alert state、余额快照等。虽然不直接涉及密钥，但如果攻击者能读取 SP 文件，可以推断用户的 API 使用模式。评估为中等影响。

---

## 二、构建与发布

### ❌ 严重-3：`minSdk = 35` — 仅支持 0.3% 的 Android 设备

**位置**: `app/build.gradle.kts:24`

```kotlin
minSdk = 35
```

Android 35 (Vanilla Ice Cream) 仅运行在 2025 年后发布的少数设备上。目前 99.7% 的 Android 设备无法安装此 App。

**数据**（截至 2026-07）：
- SDK 35: < 0.5% 设备
- SDK 34 (Android 14): ~35%
- SDK 31-33 (Android 12-13): ~40%

**如果是做给自己用（OnePlus 13，SDK 36）**：保持现状即可。

**如果要上架 Play Store**：建议最低 `minSdk = 26`（Android 8.0，覆盖 ~98% 设备），或至少 `minSdk = 31`（Android 12）。

⚠️ 降低 minSdk 后需要：
- 检查 `SCHEDULE_EXACT_ALARM` 权限（Android 12+）
- 检查 `POST_NOTIFICATIONS` 权限（Android 13+）
- 检查前台 Service 类型（Android 14+）
- 可能需要 `@RequiresApi` 或运行时权限检查

---

### ✅ 高-3：缺少版本号自动递增 — 已修复

`versionName` 使用 `git describe --tags --always --dirty` 自动生成（如 `v1.0.0`）。`versionCode` 仍为手动递增（后续 CI 可自动化）。

---

### ⚙️ 中-2：`keystore.properties` 在项目根目录

**位置**: 根目录 `keystore.properties`

包含签名密钥的密码。虽然已在 `.gitignore` 中，但仍然在 CI 工作区里。建议移到 `~/.android/` 下或使用环境变量。

---

### ⚙️ 中-3：ProGuard 规则不够完备

**位置**: `proguard-rules.pro`

缺少：
- `kotlinx.coroutines` 的 keep 规则（可能导致协程在 release build 中行为异常）
- RemoteViews Widget 相关类的 keep 规则（`BalanceWidgetDataStore`、`WidgetConfigStore` 等）

> 注意：本项目不使用 Glance（用原生 RemoteViews）、不使用 WorkManager（用 Handler + 前台 Service），因此无需对应 ProGuard 规则。

---

### ✅ 低-1：缺失 CI/CD 配置 — 已修复

GitHub Actions CI workflow 已添加，自动执行 `assembleDebug` + `testDebugUnitTest`。

---

## 三、测试与质量

### ⚠️ 高-4：UI 自动化测试覆盖不足（已有改善）

**现状**:
- 单元测试：214+ tests（引擎 + 仓库 + ViewModel + Widget + API），覆盖良好
- Instrumented 测试：4 个（`HomeScreenTest`、`OnboardingScreenTest`、`SettingsScreenTest`、`InsightsScreenTest`）
- 仍缺失的 UI 测试：LogScreen、DataManagementScreen、Widget 渲染、深链接导航

**风险**: 核心 screen 已有覆盖，但日志页、数据管理页、Widget 渲染回归仍无法自动检测。

**建议**: 补充 LogScreen 和 DataManagementScreen 的 instrumented 测试。

---

### ✅ 中-4：缺少 Widget 单元测试 — 已修复

`BalanceWidgetDataStoreTest`、`WidgetConfigStoreTest`、`WidgetProviderTest` 已添加，覆盖 Widget 数据存取和配置逻辑。

---

### ✅ 中-5：缺少网络层测试 — 已修复

`DeepSeekApiServiceTest` 已添加，覆盖 API 响应解析、错误码处理。

---

### ✅ 低-2：覆盖率基线 — 已配置 Kover

> ✅ v1.1.0 已配置：app/build.gradle.kts 第 36-60 行有完整 Kover 配置（含过滤器与验证规则）。

没有配置 JaCoCo 或 Kover 覆盖率报告。建议至少生成一份覆盖率报告，作为质量基线。

---

## 四、错误处理与韧性

### ⚠️ 高-5：`try { ... } catch (_: Exception) {}` 静默吞异常（已改善，仍有剩余）

已在 JSON 解析、数据存储等关键路径添加 `Logger.w(TAG, "operation failed", e)`。剩余约 25 处静默 catch 块。

示例位置（仍有改善空间）：
- `BalanceRefreshService.kt` — 多处 catch 块
- `HomeViewModel.kt` — 部分 catch 块已加日志
- `HomeScreen.kt` 中的 `LaunchedEffect` — while(true) 循环无异常边界

**风险**:
1. 静默失败导致数据丢失而用户毫不知情
2. 调试极其困难 — 缺少日志记录

**修复**:
- 继续为剩余静默 catch 块添加 `Logger.w`
- 对数据写入失败应该重试或通知用户

---

### ✅ 高-6：后台 Service 无异常自恢复机制 — 已修复

`ServiceHealthTracker` 已实现：
- 连续失败计数追踪
- 连续失败 ≥3 次 → 推送通知告警
- 连续失败 ≥10 次 → 自动进入保护模式（刷新间隔降至 60 分钟）
- 成功后自动重置计数器

`doRefresh()` 运行在子线程中，遇到不可恢复异常后打日志并 `scheduleNext()`。如果存储层损坏，保护模式会逐步降频并通知用户。

---

### ✅ 中-6：OkHttp 重试 — 已添加 RetryInterceptor

> ✅ v1.1.0 已修复：DeepSeekApiService.RetryInterceptor 对 GET 请求自动重试（最多 3 次，指数退避 1s/2s），覆盖 ConnectException、SocketTimeoutException、UnknownHostException、SSLException 和 5xx。

**位置**: `DeepSeekApiService.kt:18-21`

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()  // 没有 addInterceptor 做重试
```

网络瞬时故障（DNS 解析失败、TCP 连接超时）不会被自动重试。OkHttp 默认不重试。

**修复**: 添加 `RetryInterceptor` 或使用 OkHttp 的 `retryOnConnectionFailure(true)`。

---

### ✅ 中-7：JSON 反序列化无错误详情 — 已修复

已在反序列化 catch 块中添加 `Logger.w(TAG, "JSON decode failed", e)`，Release build 可追踪解析失败原因（不输出 API Key）。

---

## 五、用户体验与无障碍

### ⚙️ 中-8：未处理 TalkBack / 无障碍

- 没有为关键 UI 元素设置 `contentDescription`（部分有，但不是全部）
- Widget RemoteViews 不支持无障碍
- 图表（Canvas 手绘）完全不支持无障碍

**建议**: 为图表添加 `contentDescription` 文本摘要（如"余额过去 7 天从 ¥145 下降到 ¥120，预测 X 天后耗尽"）。

---

### ✅ 中-9：首次体验缺少引导 — 已修复

`OnboardingScreen` 已实现，首次运行展示 API Key 设置向导，引导用户获取并配置 API Key。

---

### ⚙️ 中-10：缺少深色模式适配验证

虽然有 `values-night/themes.xml`，但所有 UI 屏幕的硬编码颜色（如 `Color(0xFFFF9800)`、`Color(0xFF4CAF50)`）在深色模式下不会自动适配。建议使用 Material 3 的动态颜色（Material You）或确保所有硬编码颜色在深浅主题下都可读。

---

### ⚡ 低-3：API Key 输入框无粘贴检测

添加账户时 API Key 输入框设置了 `keyboardType = KeyboardType.Password`，但没有"从剪贴板粘贴"的引导。DeepSeek API Key 是 50+ 字符的随机字符串，用户几乎不可能手输。有些 Android 键盘在 Password 模式下禁用粘贴。

---

### ⚡ 低-4：英文 i18n 不完整

`values-en/strings.xml` 需要确认是否与 `values/strings.xml` 的 key 完全对齐。220+ 个字符串，缺一个就会 fallback 到中文。

---

## 六、合规（应用商店）

### ✅ 严重-4：隐私政策缺失 — 已修复

`PRIVACY_POLICY.md` 已编写（中英文），声明数据 100% 本地存储、零追踪。App 设置页面尚无隐私政策入口（待添加）。

---

### ✅ 高-7：`uses-permission` 声明需提供理由 — 已修复

`PLAY_CONSOLE_PERMISSIONS.md` 已编写，详细说明每个敏感权限的使用理由。Play Console 填写时可直接引用。

---

### ⚠️ 高-8：缺少 App 签名密钥安全备份

如果 `keystore.properties` 指向的 JKS/PKCS12 文件丢失且没有备份，将永远无法更新 Play Store 上的 App（需要创建新的签名密钥 → 新的 package name），所有用户数据丢失。

**修复**: 确保密钥文件有备份，最好使用 Google Play App Signing（推荐）。

---

### ✅ 中-11：package name — 已确认正确

当前 `namespace = "com.balancesentinel.app"`，从未使用 `com.example.*`。此项为审计时的误判，已确认无问题。

---

### ⚙️ 中-12：App Icon 需要适配 Play Store 规格

当前图标仅包含 5 密度的 PNG mipmap。Play Store 要求：
- 512×512 的 Play Store 图标（自适应图标）
- Feature graphic (1024×500)
- 至少 4 张截图（手机 + 7寸平板 + 10寸平板，如果声明支持）
- 自适应图标（API 26+）需要 monochrome 图层（Android 13+ 主题图标）

---

## 七、观测与监控

### ⚠️ 高-9：无线上崩溃收集

**现状**: `CrashLogger` 仅将崩溃写入本地文件。用户不主动查看就永远不知道。

**建议**: 
- 对于个人使用 App：当前方案足够
- 对于公开发布：集成 Firebase Crashlytics 或 Sentry（免费额度足够小 App 使用）
- 注意：发送崩溃堆栈到远程服务时可能包含敏感信息（API endpoint 等），需要脱敏

---

### ⚠️ 高-10：无 App 内更新提示

没有版本检查机制。发布新版本后，旧版本用户不会收到任何通知。

**建议**: 使用 Firebase In-App Messaging 或检查 GitHub Releases API 获取最新版本号，在设置页显示"新版本可用"提示。

---

### ✅ 中-13：后台任务成功率追踪 — 已修复

`RefreshStatsStore` 已实现，本地维护最近 100 次刷新的成功率环形缓冲区。可在设置页查看刷新统计仪表盘。数据不上报（隐私优先）。

---

### ⚡ 低-5：缺少 Feature Flag / 远程配置

对于公开发布的 App，建议有一个服务端开关能远程关闭特定功能（如 API endpoint 迁移、紧急关闭前台 Service 等），避免每次都发版。

---

## 八、代码质量与架构

### ⚙️ 中-14：货币符号硬编码重复

**位置**: `NotificationHelper.kt:188-190`、`BalanceRefreshService.kt:346-348`、`HomeScreen.kt:495-497` 等

`currencySymbol()` 函数在 3+ 个文件中完全重复定义。应该放在一个共享的 `CurrencyUtils` 中。

---

### ⚙️ 中-15：Widget 5 个 Provider 类高度重复

`StaticWidgetProvider_2x1, 2x2, 3x1, 4x2, 5x1` 5 个类逻辑完全相同，仅资源引用不同。可以通过一个参数化的基类减少 95% 的重复代码。

---

## 九、优先级路线图（更新后）

### 阻断上线（剩余）
| # | 问题 | 预计工时 | 状态 |
|---|---|---|---|
| 严重-1 | API Key 日志泄露 | 1h | ⚠️ 已改善（Logger 封装脱敏，CrashLogger 仍待修复） |
| 严重-3 | minSdk=35 设备兼容 | 4-8h | 如需上架则必修 |

### 上线前强烈建议（剩余）
| # | 问题 | 预计工时 | 状态 |
|---|---|---|---|
| 高-2 | 配置文件加密导出 | 2h | 待修复 |
| 高-4 | UI 自动化测试补充 | 2h | 已有改善 |
| 高-8 | 签名密钥安全备份 | 0.5h | 待确认 |
| 高-9/10 | 崩溃收集 + 更新提示 | 4h | 个人使用可跳过 |

### 首次更新（可选）
| # | 问题 | 预计工时 | 状态 |
|---|---|---|---|
| 中-8/10 | 无障碍 + 深色模式 | 6h | 待改善 |
| 中-12 | Play Store 资源（截图/图标） | 3h | 待准备 |
| 中-14/15 | 代码去重 | 2h | 待改善 |

---

## 十、附录：代码质量快速统计

| 指标 | 审计时 | v1.0.0 |
|---|---|---|
| 总源文件 | 36 Kotlin + 11 widget | ~40 Kotlin + 11 widget |
| 单元测试文件 | 16 | 22 |
| 测试数量 | 195 (0 failures) | 254+ (0 failures) |
| Instrumented 测试 | 1 | 4 |
| 静默 catch 块 | 32+ | ~25 |
| 重复函数定义 | 3+ (`currencySymbol`) | 3+ (未变) |
| 硬编码字符串（非 strings.xml）| ~15 处 | ~10 处 |
| Widget 代码重复率 | ~90% (5 providers 几乎相同) | ~90% (未变) |

---

## 结论（更新后）

**v1.2.0 发布状态**：已通过 GitHub Release v1.2.0 发布，签名正确（SHA-256 正式签名），APK 可直接安装。新增中英双语界面支持，隐私政策和所有 UI 文案均已双语化。

**对于个人使用**：当前代码质量足够。260 测试全部通过，核心逻辑经验证。严重-2（allowBackup）、高-1（证书固定）、高-2（配置脱敏）、中-6（OkHttp 重试）、低-2（Kover 覆盖率）均已在 v1.1.0 修复。仍建议修复 严重-1（日志泄露）。

**对于 Play Store 公开上线**：剩余待处理：
- 2 项阻断：日志泄露（已改善）、minSdk 适配
- 需准备 Play Store 素材（截图、Feature Graphic、图标）
- 估算剩余工时约 **5-8 小时**（较 v1.0.0 的 10-15 小时进一步减少）

**最关键的一条建议**：如果只做给自己用，当前 v1.2.0 已足够。包名已是 `com.balancesentinel.app`，无需修改。
