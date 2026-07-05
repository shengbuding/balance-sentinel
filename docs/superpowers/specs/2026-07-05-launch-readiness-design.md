# 钱包哨兵 — 上线前准备设计

日期：2026-07-05
状态：待审批
基线：81bd4d8

---

## 一、目标

将钱包哨兵从"开发完成"推进到"Play Store 可发布"，重点加固 **稳定性** 和 **合规**。

## 二、不可打破的约束

1. 所有稳定性数据（刷新成功率、崩溃日志）仅存本地
2. 不引入任何第三方分析/追踪/崩溃上报 SDK
3. 不连接任何远程服务（除 api.deepseek.com 外）
4. 现有 195 个测试保持全绿

## 三、当前状态

| 指标 | 值 |
|------|-----|
| minSdk | 35 |
| targetSdk | 35 |
| compileSdk | 35 |
| 测试 | 195 unit + 26 UI, 0 failures |
| 审计 | 33 项全部修复 |
| Play Console | 未注册 |
| keystore | 已生成，已备份 |

---

## 四、线1：稳定性加固

### 4.1 Service 异常自恢复

**问题**：`BalanceRefreshService` 存储层损坏时静默循环失败，`startLoop()` 的指数退避自毁（3h→6h→12h）粗糙。

**方案**：

- 新增 `ServiceHealthTracker`（`data/engine/ServiceHealthTracker.kt`）
  - 追踪连续失败次数（`consecutiveFailures`），存 SharedPreferences
  - 连续失败 ≥ 3 → 发送"刷新异常"通知（标题：刷新服务异常，内容：连续 N 次失败，点击跳转日志页）
  - 连续失败 ≥ 10 → 进入保护模式，降频到每小时一次
  - 保护模式下任意一次成功 → 恢复原频率，重置计数器

- 修改 `BalanceRefreshService.doRefresh()`
  - 在 catch 块中调用 `ServiceHealthTracker.recordFailure()`
  - 成功时调用 `ServiceHealthTracker.recordSuccess()`

**不引入**：远程崩溃上报、WorkManager（因为 minSdk=35 不需要向后兼容旧 API）

### 4.2 网络层 MockWebServer 测试

**问题**：`DeepSeekApiService` 零测试，API 响应解析、错误码处理、超时行为未验证。

**方案**：

- 新增 `data/api/DeepSeekApiServiceTest.kt`
- 使用 OkHttp `MockWebServer`（已有依赖，无需添加）
- 测试用例：

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 正常余额响应 | JSON → BalanceResponse 解析正确 |
| 2 | 正常用量响应 | JSON → UsageResponse 解析正确 |
| 3 | HTTP 401 | 抛出 UnauthorizedException |
| 4 | HTTP 429 | 抛出 RateLimitException |
| 5 | HTTP 500 | 抛出 ServerErrorException |
| 6 | 连接超时 | 抛出 TimeoutException |
| 7 | JSON 格式异常 | 抛出 ParseException（含字段名） |
| 8 | 空响应体 | 抛出 EmptyResponseException |

### 4.3 JSON 反序列化错误日志

**问题**：所有 `json.decodeFromString<T>()` 失败时静默 catch，无法排查。

**方案**：

- 遍历所有调用 `catch (_: Exception)` 且包含 JSON 解析的位置（约 8 处，分布在 RawRecordStore、DailySummaryStore、UsageDataStore、WidgetPrefs、ConfigManager、WidgetConfigStore、BalanceWidgetDataStore）
- 在 catch 块中添加 `Logger.w(TAG, "Failed to parse ${T::class.simpleName}: ${e.message}")`
- `Logger` 已内置 API Key 脱敏，不新增安全风险

### 4.4 本地刷新成功率仪表盘

**位置**：设置页底部新增"服务状态"卡片

**数据模型**：

```
RefreshStats {
    totalAttempts: Int       // 最近 100 次
    successes: Int
    failures: Int
    skipped: Int
    consecutiveFailures: Int // 当前连续失败
    lastSuccessTime: Long    // 上次成功时间戳
    lastAttemptTime: Long    // 上次尝试时间戳
}
```

**存储**：`RefreshStatsStore`（SharedPreferences，环形缓冲区最多 100 条）

**UI**：SettingsScreen 底部卡片，展示：
- 刷新成功率（successes / totalAttempts，百分比）
- 当前连续失败（红色高亮）
- 上次成功刷新时间（相对时间：N 分钟前）

---

## 五、线2：合规准备

### 5.1 targetSdk 评估

**现状**：targetSdk = 35（Android 15）

**评估**：
- 2026 年 8 月起 Google 要求新应用 target API 35+，我们刚好踩线
- Android 16（API 36）已发布，建议评估 upgrade 工作量和风险
- 如果 API 36 变更不大（需查阅 Android 16 行为变更文档），建议升级到 compileSdk=36 targetSdk=36，避免上线后短期内被迫升级

**决策**：先评估行为变更，再做决定。除非有 Breaking Change 需要大改，否则升级。

### 5.2 数据安全表单 × 代码一致性审计

**步骤**：

1. 运行 `./gradlew app:dependencies` 生成完整依赖树
2. 逐一审查所有依赖（包括传递依赖），确认无分析/广告/追踪库
3. 扫描代码中的隐式数据外传风险：
   - `grep -rE "WebView|webkit"' → 确认无 WebView
   - `grep -rE "URL|Uri\.parse|Intent.*http"' → 确认无自定义 URL scheme 传数据
   - `grep -rE "sendBroadcast|startActivity"' → 确认隐式 Intent 不携带敏感数据
4. 生成审计报告 `docs/audit/data-safety-audit.md`

### 5.3 权限声明视频录制指南

**前台服务演示视频**（30 秒）：

| 时间 | 画面 | 说明 |
|------|------|------|
| 0-5s | 打开 App，进入首页 | 展示已配置的账户和余额 |
| 5-10s | 下拉刷新 → 前台通知出现 | 通知栏出现"钱包哨兵服务运行中" |
| 10-20s | 切换到设置页，调整刷新间隔 | 展示用户可以控制刷新频率 |
| 20-25s | 切换回首页，等待自动刷新 | 前台通知显示刷新状态 |
| 25-30s | 展示通知栏完整内容 | 清晰可见"正在监控 X 个账户" |

**精确闹钟演示视频**（30 秒）：

| 时间 | 画面 | 说明 |
|------|------|------|
| 0-5s | 设置页 → 刷新间隔选项 | 展示 1/5/10/15/30/60 分钟选项 |
| 5-10s | 选择 1 分钟间隔 | 突出"核心功能需要精确调度" |
| 10-20s | 等待自动刷新触发 | 展示闹钟准时触发（记录时间戳对比） |
| 20-30s | 发送低余额预警通知 | 余额低于阈值时通知实时到达 |

### 5.4 Play Console 注册 + 配置清单

**注册阶段**：
1. 访问 play.google.com/console → 创建开发者账号
2. 支付 $25 一次性注册费
3. 填写开发者资料（姓名、地址）

**配置阶段**：
1. 创建应用 → 选择语言 → 填写应用名称
2. 商品详情：粘贴 PLAY_STORE_LISTING.md 内容（中英双语）
3. 应用签名：上传签名密钥，选择"让 Google 管理签名"
4. 内容分级：完成问卷（预计 Everyone）
5. 权限声明：提交前台服务 + 精确闹钟声明 + 演示视频
6. 数据安全：填写 Data Safety 表单
7. 截图：上传 5 张手机截图 + Feature Graphic + 512×512 图标
8. 隐私政策：填写 URL
9. 定价：免费
10. 提交审核

### 5.5 第三方 SDK 依赖扫描

审查依赖树，确认：
- 无 `firebase-*`（无 Firebase Analytics / Crashlytics）
- 无 `play-services-ads`
- 无 `facebook-*`
- 无任何 `analytics` / `tracking` 关键词的依赖
- OkHttp 和 kotlinx.serialization 是仅有的外部依赖

---

## 六、线3：Widget 测试覆盖

### 6.1 BalanceWidgetDataStore 测试

**文件**：`data/repository/BalanceWidgetDataStoreTest.kt`

**测试用例**：

| # | 场景 | 验证 |
|---|------|------|
| 1 | 单账户单币种 | 余额正确缓存，aggregateTopTwo 返回单币种格式 |
| 2 | 多账户同币种求和 | 同币种余额正确聚合 |
| 3 | 多币种选取 Top 2 | 按总额降序取前 2 |
| 4 | 零总额币种过滤 | 总额为 0 的币种不显示 |
| 5 | 双币种格式化 | 用 `·` 分隔 |
| 6 | 缓存过期 | 超过 TTL 返回 null |
| 7 | `__total__` 哨兵 | 总余额账户正确处理 |
| 8 | accountId+currency 双键匹配 | 同账户同币种更新不覆盖其他币种 |

### 6.2 WidgetConfigStore 测试

**文件**：`data/repository/WidgetConfigStoreTest.kt`

**测试用例**：

| # | 场景 | 验证 |
|---|------|------|
| 1 | 写入读取单个配置 | accountId、widgetId、显示选项正确 |
| 2 | 总余额选项存取 | 包含/排除总余额 |
| 3 | 多实例并发 | 不同 widgetId 互不干扰 |
| 4 | 删除配置 | removeConfig 后读取返回默认值 |
| 5 | 配置默认值 | 新 widget 首次读取返回合理默认值 |

### 6.3 Widget Provider Robolectric 测试

**文件**：`widget/WidgetProviderTest.kt`

**测试用例**（5 个 Provider 各跑一遍）：

| # | 场景 | 验证 |
|---|------|------|
| 1 | 有数据渲染 | RemoteViews 中余额 TextView 非空 |
| 2 | 布局 ID 正确 | 每个尺寸加载对应的 layout |
| 3 | 空数据 | 无账户时显示提示文案 |
| 4 | PendingIntent | 点击事件绑定到 MainActivity |

**技术方案**：
- 使用 Robolectric `ShadowApplication` 模拟 widget 环境
- `AppWidgetManager` shadow 获取 `RemoteViews`
- 遍历 RemoteViews 的 `viewActions` 验证 `setTextViewText` 等操作
- 不依赖真机，可在 `testDebugUnitTest` 中运行

---

## 七、发布检查单

上线前逐项确认：

### 代码与构建
- [ ] Service 异常自恢复实现 + 测试通过
- [ ] 网络层 MockWebServer 测试 8 个用例通过
- [ ] JSON 反序列化错误日志补完
- [ ] 本地刷新成功率仪表盘实现
- [ ] Widget 测试 ~20 个用例通过
- [ ] `./gradlew testDebugUnitTest` 全部通过（预期 215+ tests）
- [ ] `./gradlew assembleRelease` 成功生成 AAB

### 合规
- [ ] targetSdk 决策完成（35 或 36）
- [ ] 数据安全一致性审计报告通过
- [ ] 第三方依赖扫描通过（零分析/追踪/广告 SDK）
- [ ] 权限声明视频已录制

### Play Console
- [ ] 开发者账号已注册
- [ ] 商品详情已填写（中英双语）
- [ ] 签名密钥已上传（Google 管理签名）
- [ ] 内容分级已填写
- [ ] 权限声明 + 视频已提交
- [ ] Data Safety 表单已填写
- [ ] 截图 + 图标 + Feature Graphic 已上传
- [ ] 隐私政策 URL 可访问
- [ ] 提交审核

---

## 八、不在范围内

以下内容明确不做：

- 降低 minSdk（保持 35，覆盖最新 Android 设备）
- 引入 Firebase / Sentry 等远程崩溃收集
- 引入远程配置 / Feature Flag
- 多设备 OEM 兼容测试
- 长时间压力测试（24h+）
- 应用内更新提示
