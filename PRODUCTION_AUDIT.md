# 钱包哨兵 — 上线前全面审计

日期：2026-07-05
审计范围：所有源码、资源、构建配置、测试、架构

---

## 总览

| 类别 | 严重 | 高 | 中 | 低 |
|---|---|---|---|---|
| 安全与隐私 | 2 | 2 | 1 | — |
| 构建与发布 | 1 | 1 | 2 | 1 |
| 测试与质量 | — | 1 | 2 | 1 |
| 错误处理与韧性 | — | 2 | 2 | — |
| 用户体验与无障碍 | — | — | 3 | 2 |
| 合规（应用商店） | 1 | 2 | 2 | — |
| 观测与监控 | — | 2 | 1 | 1 |
| 代码质量与架构 | — | — | 2 | — |

共 **24 项**，其中严重 4 项、高 10 项。

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

### ❌ 严重-2：`allowBackup="true"` 导致加密密钥可被提取

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

### ⚠️ 高-1：OkHttp 未启用证书固定 (Certificate Pinning)

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

### ⚠️ 高-3：缺少版本号自动递增

**位置**: `app/build.gradle.kts:27-28`

```kotlin
versionCode = 1
versionName = "1.0.0"
```

硬编码，每次发布需要手动改。容易忘记递增导致 Play Store 拒绝上传。

**修复**: 使用 git commit count 或 CI 环境变量：
```kotlin
versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
// 或
versionCode = gitCommitCount()  // 自定义函数
```

---

### ⚙️ 中-2：`keystore.properties` 在项目根目录

**位置**: 根目录 `keystore.properties`

包含签名密钥的密码。虽然已在 `.gitignore` 中，但仍然在 CI 工作区里。建议移到 `~/.android/` 下或使用环境变量。

---

### ⚙️ 中-3：ProGuard 规则不够完备

**位置**: `proguard-rules.pro`

缺少：
- `kotlinx.coroutines` 的 keep 规则（可能导致协程在 release build 中行为异常）
- Glance Widget 的反射规则
- 如果未来引入 WorkManager，需要对应的 keep 规则

---

### ⚡ 低-1：缺失 CI/CD 配置

项目没有 CI 配置文件（GitHub Actions `.yml`、GitLab CI 等）。每次构建需要在本地执行 `./gradlew.bat assembleDebug testDebugUnitTest`。

**建议**: 添加 GitHub Actions workflow，自动执行 `assembleDebug` + `testDebugUnitTest` + `lint`。

---

## 三、测试与质量

### ⚠️ 高-4：无 UI 自动化测试（除 1 个 HomeScreen test）

**现状**:
- 单元测试：195 tests（引擎 + 仓库 + ViewModel），覆盖良好
- Instrumented 测试：1 个（`HomeScreenTest.kt`）
- 缺失的 UI 测试：InsightsScreen、SettingsScreen、LogScreen、DataManagementScreen、Widget 渲染、深链接导航

**风险**: UI 回归在发版前无法自动检测。尤其是 Compose 动画状态、深链接和 Widget RemoteViews 渲染。

**建议**: 至少覆盖核心 happy path（打开 App → 添加 Key → 查看余额 → 打开 Insights → 切换时间范围）。

---

### ⚙️ 中-4：缺少 Widget 单元测试

Widget 使用 RemoteViews（非 Compose），无法用 Compose Testing API 测试。`StaticWidgetProvider`、`BalanceWidgetDataStore`、`WidgetConfigStore`、`WidgetErrorLogger` 都没有对应的测试类。

**建议**: 至少测试 `BalanceWidgetDataStore` 的数据存取逻辑。

---

### ⚙️ 中-5：缺少网络层测试

`DeepSeekApiService` 没有单元测试 — 没有测试 API 响应解析、错误码处理、超时行为。

**建议**: 使用 MockWebServer (OkHttp) 编写 API 层测试。

---

### ⚡ 低-2：无代码覆盖率基线

没有配置 JaCoCo 或 Kover 覆盖率报告。建议至少生成一份覆盖率报告，作为质量基线。

---

## 四、错误处理与韧性

### ⚠️ 高-5：`try { ... } catch (_: Exception) {}` 静默吞异常（32+ 处）

整个代码库中频繁使用 `catch (_: Exception) {}` 忽略所有异常。

示例位置：
- `BalanceRefreshService.kt:91,212,243,262,292,329,330`
- `HomeViewModel.kt:126,137,146,153,198,356,370,407,408,418,444`
- `HomeScreen.kt` 中的 `LaunchedEffect` — 有一个无限 `while(true)` 循环，没有异常边界

**风险**:
1. 静默失败导致数据丢失而用户毫不知情
2. 调试极其困难 — 没有任何日志记录
3. `catch (_: Exception) {}` 会吞掉 `OutOfMemoryError` 的子类（在 Kotlin 中 Error 也继承自 Throwable，但 Exception 不会），但会吞掉 NPE、IllegalState 等关键崩溃信息

**修复**:
- 至少添加 `Log.w(TAG, "operation failed", e)` 
- 使用自定义 `NonFatalException` 包装可恢复的错误
- 对数据写入失败应该重试或通知用户

---

### ⚠️ 高-6：后台 Service 无异常自恢复机制

**位置**: `BalanceRefreshService.kt:156-295`

`doRefresh()` 运行在子线程中，遇到不可恢复异常后只会打 Log 然后 `scheduleNext()`。如果存储层损坏（如 SharedPreferences 文件损坏导致 JSON 解析失败），Service 会每 N 分钟循环失败，但没有任何降级或告警。

此外，`startLoop()` 的指数退避自毁（3h → 6h → 12h）是一个粗糙的节电策略，但会导致用户长时间看不到刷新。建议在 Service 真的无法恢复时才自毁，并在自毁前通过通知告知用户。

---

### ⚙️ 中-6：OkHttp 无重试机制

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

### ⚙️ 中-7：JSON 反序列化无错误详情

**位置**: 多处 `json.decodeFromString<T>(raw)`

解析失败时 `kotlinx.serialization` 会抛出 `SerializationException` 但当前代码都是 `catch (_: Exception) { null/empty }`。不知道到底是什么字段解析失败了。

**建议**: Release 中 catch 异常但至少 log 异常消息（不要 log API Key）。

---

## 五、用户体验与无障碍

### ⚙️ 中-8：未处理 TalkBack / 无障碍

- 没有为关键 UI 元素设置 `contentDescription`（部分有，但不是全部）
- Widget RemoteViews 不支持无障碍
- 图表（Canvas 手绘）完全不支持无障碍

**建议**: 为图表添加 `contentDescription` 文本摘要（如"余额过去 7 天从 ¥145 下降到 ¥120，预测 X 天后耗尽"）。

---

### ⚙️ 中-9：首次体验缺少引导

`EmptyAccountsHint()` 只有一个空状态卡片 + FAB。新用户不知道：
1. API Key 从哪里获取
2. 这个 App 是做什么的
3. Widget 如何添加到桌面

**建议**: 添加一个 3 页的引导页（Onboarding），或至少在主屏幕增加获取 API Key 的链接。

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

### ❌ 严重-4：隐私政策缺失

**Google Play 要求**: 所有 App 必须有隐私政策链接。使用网络权限（`INTERNET`）的 App 必须声明数据收集和共享。

**现状**: 无任何隐私政策页面、隐私政策链接，或无在 App 内展示隐私政策的机制。

**修复**:
1. 编写简短的中/英文隐私政策（或使用 GitHub Pages 托管）
2. 在 Play Console 填写隐私政策 URL
3. 在 App 设置页面添加"隐私政策"入口

---

### ⚠️ 高-7：`uses-permission` 声明需提供理由

Google Play 对 `SCHEDULE_EXACT_ALARM`、`FOREGROUND_SERVICE`、`RECEIVE_BOOT_COMPLETED` 要求声明使用理由。需要在 Play Console 中逐个填写，并在 App 内解释为什么需要这些权限。

特别注意 `SCHEDULE_EXACT_ALARM` — Google 对此权限审核严格，必须在 Play Console 中提交视频演示。

---

### ⚠️ 高-8：缺少 App 签名密钥安全备份

如果 `keystore.properties` 指向的 JKS/PKCS12 文件丢失且没有备份，将永远无法更新 Play Store 上的 App（需要创建新的签名密钥 → 新的 package name），所有用户数据丢失。

**修复**: 确保密钥文件有备份，最好使用 Google Play App Signing（推荐）。

---

### ⚙️ 中-11：`package name = com.example.*`

**位置**: `app/build.gradle.kts:19`

```kotlin
namespace = "com.example.deepseekbalance"
applicationId = "com.example.deepseekbalance"
```

`com.example.*` 是保留命名空间，Google Play **不允许**发布。需要改为你自己的域名（如 `com.yourdomain.balancesentinel` 或 `io.github.yourname.deepseekbalance`）。

⚠️ 改 package name 需要：更新所有源文件的 package 声明、AndroidManifest 中的组件引用、Gradle namespace、Widget provider 的 `android:name`、测试文件的 imports。建议使用 Android Studio 的 Refactor → Rename 功能。

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

### ⚙️ 中-13：后台任务成功率无法追踪

`RefreshScheduler` 有 alarm counter（set/fired/overwritten/dropped），但这些数据仅存储在本地 SP 中，不上报。不知道：
- 实际送达率是多少
- 哪个设备/OEM 的闹钟被系统杀死最频繁
- 用户实际看到的刷新频率是多少

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

## 九、优先级路线图

### 阻断上线（必须先修）
| # | 问题 | 预计工时 |
|---|---|---|
| 严重-1 | API Key 日志泄露 | 1h |
| 严重-2 | allowBackup=true | 0.5h |
| 严重-3 | package name (com.example) | 2h |
| 严重-4 | 隐私政策 | 2h |

### 上线前强烈建议
| # | 问题 | 预计工时 |
|---|---|---|
| 高-1 | HTTPS 证书固定 | 1h |
| 高-2 | 配置文件加密导出 | 2h |
| 高-4 | UI 自动化测试 | 4h |
| 高-5 | 异常静默处理 | 3h |
| 高-7/8 | Play Console 权限声明 + 签名备份 | 2h |
| 高-9/10 | 崩溃收集 + 更新提示 | 4h |

### 首次更新
| # | 问题 | 预计工时 |
|---|---|---|
| 中-8/9/10 | 无障碍 + 引导页 + 深色模式 | 8h |
| 中-11 | Play Store 资源（截图/图标） | 3h |
| 中-14/15 | 代码去重 | 2h |
| 低-1 | CI/CD | 2h |

---

## 十、附录：代码质量快速统计

| 指标 | 数值 |
|---|---|
| 总源文件 | 36 Kotlin files + 11 widget files |
| 测试文件 | 16 |
| 测试数量 | 195 (0 failures) |
| 静默 catch 块 | 32+ |
| 重复函数定义 | 3+ (`currencySymbol`) |
| 硬编码字符串（非 strings.xml）| ~15 处 |
| Widget 代码重复率 | ~90% (5 providers 几乎相同) |

---

## 结论

**对于个人使用**：当前代码可以直接装到自己的 OnePlus 13 上用。195 个测试全部通过，核心逻辑经过验证。只需要修严重-1（日志泄露）就够了。

**对于 Play Store 公开上线**：需要完成上述 4 项阻断问题 + 至少 6 项高优先级问题。估算总工时约 **20-25 小时**（一人全职约 3-4 天）。最大的单项工作是降低 minSdk 并验证兼容性（如果需要扩大用户群），以及编写 UI 自动化测试。

**最关键的一条建议**：如果只做给自己用，保持现状，只需修 `allowBackup=false` + `package name`（虽然不上架但 class name 有 `example` 很别扭）+ 日志泄露即可。
