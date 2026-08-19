# Claude Code v1.5.1 发布审查交接

更新日期：2026-08-20

状态：v1.5.1 发布候选资料已完成，保留 v1.5.0 历史部分作为独立审查证据索引。

## v1.5.1 发布补充

- 发布分支：`release/v1.5.1`；目标分支：`master`。
- 本地门禁：Debug/Release JVM 各 1,531 tests，0 failures，0 errors，3 skipped；Debug/Release lint 通过；Kover verify 通过；Debug/Release APK 构建成功。
- 主要范围：OpenCode Go 内置订阅窗口、订阅洞察和小组件、跨账户历史合并、订阅百分比 Room 归档语义，以及 OpenAI API 与 ChatGPT 订阅额度边界文档。
- 正式签名 APK、包版本校验、证书校验和 GitHub Release 资产由 `v1.5.1` tag workflow 生成；最终 SHA-256 以 Actions 和 Release 资产为准。
- 全量 API 35 connected-test discovery 仍受本机模拟器启动限制，未将其计为通过。

以下正文中的 v1.5.0 内容是历史审查记录，不应被解释为 v1.5.1 的当前 HEAD。

## 一、审查目标与操作边界

请对 `v1.5.0` 标签中的实现提交做独立的正确性、安全性、并发、性能、迁移与测试覆盖审查。正式发布后，本文档、记忆追记、README 和发布报告也作为证据索引；需要区分标签内源码与发布后文档补充。

以下约束适用于正式发布前的 Claude Code 审查阶段；正式发布动作已由维护者完成：

- 不得重复执行 `git push`、创建重复 Release 或上传重复 APK。
- 不得重置、清理或覆盖当前工作树；不得修改标签。
- 默认不修改源码。如后续获得明确修复授权，应将每个修复与审查 finding 对应，并单独验证。
- 不读取、打印或复制 `keystore.properties`、`.jks` 内容、密码、API Key、Cookie 或会话数据。
- 默认不重建 Release APK。只有在明确获得签名构建授权且本机已安全配置签名材料时，才可执行 `assembleRelease`。

## 二、基线快照

| 项目 | 已核实值 |
|---|---|
| 仓库 | `C:\Users\Administrator\DeepSeekBalance` |
| 分支 | `master` |
| HEAD / release commit | 以 annotated `v1.5.0` 的 peeled commit 为准（`git rev-parse v1.5.0^{}`） |
| 实现提交 | `d8a3f01c21901694e11e8ac68571b06cfe6be17b` (`feat: complete wallet sentinel review fixes`) |
| 发布文档提交 | 与 `v1.5.0` 指向同一最终发布提交 |
| 发布标签 | annotated `v1.5.0`，本地与 GitHub 均应指向最终发布提交 |
| 标签说明 | `v1.5.0 formal release` |
| 与现有 `origin/master` 引用的关系 | 本地 `master` ahead 501，behind 0 |
| 远程状态 | 推送 `master` 与 `v1.5.0` 后由 tag workflow 创建 Release 并上传 APK；结果以 Release URL 和 Actions 运行记录为准 |

正式发布提交完成后，工作树应只包含后续审查记录或新的文档修订；发布提交前的本地预发布文档差异包括：

- `README.md`
- `memory/2026-08-15-wallet-sentinel-review-fix.md`
- `docs/claude-code-review-handoff.md`

## 三、产物与验证证据

正式发布资产由 `v1.5.0` tag workflow 在 GitHub Actions 中重新签名构建；
最终字节数和 SHA-256 以 GitHub Release 资产及对应 Actions 运行记录为准。
下面的本地 APK 仅是发布前快照，不能当作正式 Release 资产的校验值：

`C:\Users\Administrator\DeepSeekBalance\app\build\outputs\apk\release\app-release.apk`

| 属性 | 值 |
|---|---|
| applicationId | `com.balancesentinel.app` |
| versionName | `v1.5.0` |
| 快照来源 | `0cdcb69`（本地预发布标签） |
| versionCode | `691`（仅历史基线） |
| 字节数 | `16,800,134`（仅历史基线） |
| SHA-256 | `3F8DC76B4D0263C18ABB8C777056BDBA04EBEDBA6A82D6870ED8F12879802F24`（仅历史基线） |
| APK 签名 | Signature Scheme v2 验证通过（历史基线） |
| 证书 SHA-256 | `319aa8dae339e8c95e5538331605550d7abc94992cbeb5ce54b74b276ccbad3f`（正式工作流允许的证书指纹） |

正式发布验证基线：

- Debug JVM：1,486 tests，0 failures，0 errors，3 skipped。
- Release JVM：1,486 tests，0 failures，0 errors，3 skipped；已对标签树重跑并通过。
- Debug/Release lint：0 errors。
- Kover verify：通过；行覆盖率 58.96%，分支覆盖率 48.79%。
- 目标 `MainActivityTest` 设备测试：4/4 通过。
- API 35 模拟器上的全量 `connectedDebugAndroidTest` 两次在发现 0 个测试时启动崩溃，未进入断言；这不应被表述为全量仪器测试通过。
- 安全审查未确认置信度大于等于 7/10 的阻断项；签名材料和密码未进入 Git。

## 四、主要变更范围

`d8a3f01` 涉及 96 个文件，约 `+5507/-662`，主要包括：

1. Android 15 前台监控、精确闹钟、保留通知与 Worker 恢复。
2. 所有账户类型的充值/消耗语义，以及 Insights 当前币种、账户资格和跨账户图表。
3. 自定义 Usage Script 字段投影、卡片名称、余额计算字段、NewAPI 预设与额外凭据。
4. Room v6 schema、通知总额虚拟行排序、旧设置迁移与配置导入兼容。
5. 后台刷新开关、首次权限引导、授权状态、Widget/deep-link、Console 会话清理。
6. 设置与预警页面重排、窄屏账户行对齐、中英文资源及相关测试。
7. 上位机配置/历史数据同步的传输无关契约和安全策略预留；当前没有任何网络传输或前端入口。

## 五、优先审查边界

以下是需要独立验证的边界，不是已确认 bug 清单。请先复现和建立证据，再上报 finding。

### P0：后台监控与通知存活

- `app/src/main/java/com/balancesentinel/app/receiver/KeepAliveReceiver.kt:45`：`reconcile`、精确闹钟允许/拒绝、`SecurityException` 回退、OEM 90/120 秒周期与取消语义。
- `app/src/main/java/com/balancesentinel/app/service/BalanceRefreshService.kt:105`：平台超时、2 秒持久化 deadline、用户显式停止、`DETACH/REMOVE` 和重复停止竞态。
- `app/src/main/java/com/balancesentinel/app/service/ContinuousMonitoringController.kt:22`：`start`/`stop`/`onPlatformTimeout` 对 desired state 的保存与恢复次序。
- `app/src/main/java/com/balancesentinel/app/work/RefreshWorker.kt:64`：禁用监控、刷新失败或并发 opt-out 时，不应重建已取消的通知。

产品决定：90/120 秒精确闹钟是用户明确要求的强保活策略。审查仍应报告每日约 720–960 次唤醒的电量成本、Doze 配额与 OEM 限制；不应声称 Android 保证该周期。

### P0：Insights 正确性与并发

- `app/src/main/java/com/balancesentinel/app/ui/viewmodel/InsightsViewModel.kt:184`：快速切换账户/币种时，不响应取消的仓库不得用旧结果覆盖新选择、`SavedStateHandle`、币种集或 loading 状态。
- `app/src/main/java/com/balancesentinel/app/ui/viewmodel/InsightsViewModel.kt:490`：回归检查“全部账户”的首点必须从所有可见账户都有首样本的时刻开始，不得由 0 连到总余额，也不得把未来样本回填到更早时间。
- `app/src/main/java/com/balancesentinel/app/ui/viewmodel/InsightsViewModel.kt:213`：单账户视图不应为全部账户读取摘要和 24 小时原始历史；请核对 DAO 过滤与内存分配。
- `app/src/main/java/com/balancesentinel/app/data/engine/RecordAggregator.kt:93` 与 `app/src/main/java/com/balancesentinel/app/data/local/history/HistoryDao.kt:341`：Kotlin 与 SQL 的充值/消耗阈值、metadata 缺失、浮点边界必须一致。

### P1：自定义账户与配置持久化

- `app/src/main/java/com/balancesentinel/app/data/model/UsageFieldConfig.kt:9`：字段编解码、空标签、重复/无效行、恶意或损坏 JSON。
- `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt:132`：嵌套 JSON 路径、数组、0余额、非有限数、额外 credential 回退、响应与脚本尺寸限制。
- `app/src/main/java/com/balancesentinel/app/data/api/providers/OpenAiCompatibleProvider.kt:84`：展示字段白名单、自定义名称与指定余额字段应穿透到卡片、历史和洞察。
- `app/src/main/java/com/balancesentinel/app/ui/components/AddAccountDialog.kt:82` 与 `app/src/main/java/com/balancesentinel/app/ui/components/EditAccountDialog.kt:87`：无效脚本、错误余额路径与格式错误字段目前可能到刷新时才反馈；请区分确认的可用性问题和产品校验取舍。
- 核对 `AccountInfo`/Room mapper/导出导入/编辑保存全链路，确保 `usageDisplayFields` 与 `usageBalanceField` 不丢失。

### P1：设置、权限、迁移与同步契约

- `app/src/main/java/com/balancesentinel/app/ui/viewmodel/HomeViewModel.kt:757`：后台刷新开关的禁用→修改周期→重新启用语义以及持久化值。
- `app/src/main/java/com/balancesentinel/app/platform/permission/AndroidCapabilityChecker.kt:48`：Android M/S 前后精确闹钟与电池优化 API 分支，及系统设置 Intent 不可用/用户拒绝。
- `app/src/main/java/com/balancesentinel/app/data/local/WalletDatabase.kt:195`：Room 5→6、旧设置迁移、导入与运行时账户过滤对通知总额位置的重映射必须同义。
- `app/src/main/java/com/balancesentinel/app/data/sync/DesktopSyncContracts.kt:86`：要求对 schema、ID、nonce、digest、未来签发、过期、正向有效期、最大生命周期和 CONFIG/HISTORY 大小边界逐一拒绝。
- `docs/desktop-sync-contract.md:3`：请确认代码仍只有内部契约，没有监听端口、网络传输、远程控制或前端入口。

### P1：启动、Console 与 UI

- `app/src/main/java/com/balancesentinel/app/MainActivity.kt:87`：`runBlocking(Dispatchers.IO)` 在 UI 线程等待 Room/凭据协调，评估冷启动阻塞。
- `app/src/main/java/com/balancesentinel/app/data/console/store/ConsoleStore.kt:132`：`SharedPreferences.commit()` 可由主线程登出/清理路径调用，评估磁盘阻塞及 Cookie/WebStorage 异步清理的完成语义。
- `app/src/main/java/com/balancesentinel/app/ui/screen/AlertSettingsScreen.kt:545`：回归验证 320dp 与大字体下表头、货币、通知排序行、余额/异动开关对齐且无溢出。
- Add/Edit 的自定义脚本开关与预警控件应有稳定的语义名称和足够触控区域。

## 六、已知测试缺口

变更路径审计估算约 69% 覆盖，优先补充以下九类边界：

1. `KeepAliveReceiver` 精确闹钟允许/拒绝、`SecurityException` 回退、OEM 分支与广播入口。
2. `BalanceRefreshService` 平台超时、deadline 先完成、持久化失败与重复停止竞态。
3. 精确闹钟/电池优化 capability 的 API 等级矩阵与解决事件。
4. `UsageFieldConfig` 编解码、解析、Room 往返与导入导出。
5. 自定义展示字段在 provider、ViewModel 和卡片的投影。
6. 后台刷新开关禁用、修改周期与重新启用。
7. 通知总额虚拟行在删除账户与交叉移动时的排序持久化。
8. `DesktopSyncPolicy` 全部拒绝原因与 4 MiB/256 MiB 大小边界。
9. `InsightsViewModel` 非取消敏感旧加载覆盖新状态的竞态。

## 七、建议验证命令

先执行不改变状态的 Git 核对：

```powershell
Set-Location -LiteralPath 'C:\Users\Administrator\DeepSeekBalance'
git status --short --branch
git show --check d8a3f01
git show --check 0cdcb69
git diff --check d8a3f01^ d8a3f01
git diff --check 0cdcb69^ 0cdcb69
git show --stat d8a3f01
git show --stat 0cdcb69
git cat-file -p refs/tags/v1.5.0
git rev-list -n 1 v1.5.0
```

核对当前文档差异：

```powershell
git diff --check
git diff -- README.md memory/2026-08-15-wallet-sentinel-review-fix.md docs/claude-code-review-handoff.md
```

核对 APK 哈希：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\app\build\outputs\apk\release\app-release.apk'
```

在不需要签名材料的前提下运行本地门禁：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest --offline --no-daemon
.\gradlew.bat :app:lintDebug :app:lintRelease --offline --no-daemon
.\gradlew.bat :app:koverVerify --offline --no-daemon
```

优先目标测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.balancesentinel.app.service.BalanceRefreshServiceTest --offline --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest --offline --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest --offline --no-daemon
```

## 八、审查输出契约

请以 findings 为先，按严重程度降序。每个 finding 至少包含：

- 严重度：`Blocker` / `High` / `Medium` / `Low`。
- 置信度：0–10；请明确区分“已确认缺陷”、“产品/平台限制”和“测试缺口”。
- 精确的 `file:line` 与符号名。
- 可观察影响、触发条件和最小复现步骤。
- 为什么现有测试没有捕获它。
- 最小修复方向和建议新增的测试。

已经修复的“Insights 从 0 起笔”和“预警账户行不对齐”应作为回归检查；只有当前代码可复现时才作为 finding 重新上报。

若没有发现可操作问题，请明确写出 `No findings`，并单独列出未能验证的真机/OEM/签名/全量仪器测试残余风险。

## 九、正式发布结果

- GitHub Release：[v1.5.0](https://github.com/shengbuding/balance-sentinel/releases/tag/v1.5.0)
- 发布方式：推送 `v1.5.0` tag，触发 `.github/workflows/release.yml`。
- Release workflow 负责 Debug/Release JVM、Debug/Release lint、Kover、签名构建、APK 包名/版本校验、证书 allowlist 校验、CHANGELOG Release 正文、Release 创建和 APK 上传。
- 最终提交和 tag 目标以 `git rev-parse v1.5.0^{}` 为准；本文件不复制任何签名秘密。

## 十、相关文档

- 完整工作记忆：`memory/2026-08-15-wallet-sentinel-review-fix.md`
- 上位机同步安全契约：`docs/desktop-sync-contract.md`
- 签名与验签：`SIGNING.md`
- 目标设备测试记录：`TEST_REPORT.md`
- 已接受的历史发布残余：`RELEASE_REVIEW_REPORT.md`
