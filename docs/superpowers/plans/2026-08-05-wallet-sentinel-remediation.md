# 钱包哨兵架构修复实施计划

> **执行要求：** 必须使用 `superpowers:subagent-driven-development` 按任务执行。每个行为修改先出现可解释的行为级 RED，再完成最小 GREEN；任务完成后由独立审查者检查。清单使用 `- [ ]` 跟踪。

**目标：** 修复全量架构审查确认的数据损坏、不可恢复迁移、历史存储扩展性、后台调度、错误成功状态、导航与生命周期、下载并发、签名升级、本地化和无障碍问题。

**设计：** 以 Room 作为账户元数据和非敏感业务数据主存储，以分代加密存储保存凭据，通过持久化操作台账执行“暂存、发布、清理”。普通后台工作由 WorkManager 恢复，持续监控由始终在前台的服务承担。界面只消费持久化领域状态和类型化结果。

**技术栈：** Kotlin 2.1、Java 17、Android API 35、Jetpack Compose、Room 2.7.2、WorkManager 2.10.1、Navigation Compose 2.8.9、OkHttp 4.12、kotlinx.serialization 1.7、JUnit 4、Robolectric、MockK、MockWebServer、Compose UI Test。

**设计规格：** `docs/superpowers/specs/2026-08-05-wallet-sentinel-remediation-design.md`

**Room v1 canonical 规格：** `docs/superpowers/specs/2026-08-06-wallet-sentinel-room-v1-schema-design.md`

## 全局约束

- 仅在 `C:\Users\Administrator\DeepSeekBalance\.worktrees\wallet-sentinel-hardening` 和 `wallet-sentinel-hardening` 分支工作。
- 当前计划基线为 `4c6a9581ed8484f11f3ff7b94630601b17354953`。禁止嵌套创建 worktree。
- 控制器不修改生产源码。每个实现任务使用 fresh implementer；首次审查后的前三轮修复恢复同一 implementer。
- 当前任务的 spec/quality review 未批准，或仍有未处理 Critical/Important 时，禁止派发下一任务。
- 每个行为修复提交顺序为：行为级 RED、RED 提交、最小 GREEN、聚焦测试、GREEN 提交、独立任务审查。
- 新基础设施若无法通过现有入口形成行为 RED，必须标记为“支持任务”，只建立编译和测试夹具，不得顺带改变产品行为。
- 需要全新类型的行为任务先做独立 support commit：只加入接口、模型、依赖注入 seam 和转发到旧实现的无行为 adapter；随后必须通过旧 Application/Repository/UI 入口得到可编译的行为 RED。
- RED 必须因目标旧行为失败，不能因新类不存在、测试夹具错误、编译错误或环境错误失败。
- Gradle 串行运行，使用 `--no-parallel`；禁止同时运行两个 `GradleWrapperMain`。
- 每个任务提交前运行计划列出的聚焦测试和 `git diff --check`。阶段门禁使用 `--rerun-tasks`。
- 不使用 `allowMainThreadQueries()`、生产 `runBlocking`、无界 `readBytes()` 或文本响应 `body.string()`。
- Room schema v1 在第一次引入时严格包含 canonical 规格冻结的 19 张表；后续任务可以增加 DAO 查询，但不得修改 v1 表、列、索引、外键、默认值或持久枚举字面量。
- 稳定账户 UUID 不再由 API Key 推导。阶段 2 完成前保留 nullable `legacyStorageId`，旧数据通过该映射读取和清理。
- API Key 编辑只切换凭据 generation，不改变账户 UUID。
- `ConfigSettings` 进入 Room `app_settings`，否则不得宣称账户与设置原子导入。
- 纯安全记录“APK SHA-256/签名预检”和“Console TTL 遇系统时间回拨”不进入实现。
- Release 证书 pin 只能从可信、实际证书链提取和复核，禁止臆造备用 hash。
- 每个任务报告必须记录 RED 命令及预期失败、GREEN 命令及计数、提交范围、未解决风险和审查结论。
- 每个阶段开始前把 `Phase <N> base: <40位SHA>` 写入本计划专属 progress ledger；阶段门禁和 review package 只能从该行读取基线，不使用 `HEAD~1` 或字面占位符。

## 固定跨存储协议

账户新增、编辑和导入必须使用：

1. `mutation_operations` 写 `PREPARED`，并在任何外部写入前保存完整目标和 staged generation manifest。
2. generation key 固定由 `operationId + accountId` 生成；新凭据写入未被引用的 generation，并完整读回校验。
3. Room 单事务写账户、active generation **引用**、设置和 `localRevision`，操作进入 `PUBLISHED`。
4. 返回用户可见成功。
5. 清理旧 generation 和旧存储残留，操作进入 `COMPLETED`。

发布前失败删除 staged generation，发布后失败只重试清理。删除操作先在 Room 事务中隐藏账户并级联拥有数据，随后清理凭据和旧缓存。

---

## 阶段 1：数据安全和可恢复账户事务

创建本计划 ledger 后，立即写入 `Phase 1 base: <git rev-parse HEAD 的完整输出>`。

### Task 1：凭据三态与损坏旧账户写保护

**文件：**

- Create: `app/src/main/java/com/balancesentinel/app/data/credentials/CredentialModels.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/credentials/CredentialStore.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/credentials/EncryptedPreferencesCredentialStore.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/migration/LegacyAccountSource.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/ApiKeyManager.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/credentials/EncryptedPreferencesCredentialStoreTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/migration/LegacyAccountSourceTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/ApiKeyManagerTest.kt`

**接口：** `CredentialReadResult.Missing/Valid/Corrupt`、`CredentialPayload`、`CredentialGeneration`、`DataCorruptionException`。

- [ ] 在现有 `ApiKeyManager` 测试中写入损坏 `accounts` JSON，验证 save/delete/rename/update/migrate/clear 都失败且原始字符串逐字节不变。
- [ ] 为不存在、合法、认证失败、解密失败、JSON 失败和字段校验失败写三态测试；先通过现有入口观察行为 RED。
- [ ] 提交 RED，禁止生产文件混入。
- [ ] 实现三态读取和 fail-closed 写门；不自动修复、不吞异常、不把 `Corrupt` 转为空列表。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStoreTest" --tests "com.balancesentinel.app.data.migration.LegacyAccountSourceTest" --tests "com.balancesentinel.app.data.repository.ApiKeyManagerTest" --rerun-tasks --no-parallel
```

### Task 2：Room v1 完整骨架与事务元数据（支持任务）

**唯一契约：** 本任务逐字实现
`docs/superpowers/specs/2026-08-06-wallet-sentinel-room-v1-schema-design.md`。
该文件冻结全部列、Kotlin/SQLite 类型、默认值、PK、索引、FK、枚举字面量、
DAO 最小 API、发布 DTO、事务顺序和测试门禁；本计划不另造第二份 schema 定义。

**已完成构建支持：** 提交 `21aa686` 已在 `gradle/libs.versions.toml` 和
`app/build.gradle.kts` 固定 Room 2.7.2、kapt、schema export 与 Room testing，
并通过 `compileDebugKotlin --rerun-tasks --no-parallel`。不得重复或改写该提交。

**生产文件：**

- Create: `app/src/main/java/com/balancesentinel/app/data/local/WalletDatabase.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/WalletDatabaseProvider.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/DatabaseConverters.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/account/AccountEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/account/AccountDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/mutation/MutationOperationEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/mutation/MutationOperationDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/metadata/AppMetadataEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/metadata/AppMetadataDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/AppSettingsEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/AppSettingsDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/AccountAlertSettingEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/NotificationWalletSelectionEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/AlertRuntimeStateEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/SnoozeStateEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/settings/SettingsDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/history/BalanceRecordEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/history/DailySummaryEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/history/HistoryDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/usage/UsageSnapshotEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/usage/UsageRecordEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/usage/UsageDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/log/EventLogEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/log/EventLogDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/refresh/RefreshRunEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/refresh/RefreshAccountResultEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/refresh/RefreshRunDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/update/DownloadOperationEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/update/DownloadOperationDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/maintenance/MaintenanceCheckpointEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/maintenance/MaintenanceCheckpointDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/monitoring/MonitoringStateEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/monitoring/MonitoringStateDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/monitoring/MonitoringSessionEntity.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/monitoring/MonitoringSessionDao.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/publication/MutationPublication.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/publication/MutationPublisher.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/publication/TransactionStepObserver.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/local/publication/PublicationConflictException.kt`
- Create: `app/schemas/com.balancesentinel.app.data.local.WalletDatabase/1.json`

**测试文件：**

- Create: `app/src/test/java/com/balancesentinel/app/data/local/WalletDatabaseTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/local/DatabaseConvertersTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/local/account/AccountDaoTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/local/publication/MutationPublisherTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/local/monitoring/MonitoringSessionDaoTest.kt`

**接口：**

- Consumes: canonical 规格中的稳定 UUID、19 表 identity、14 组稳定枚举/provider
  字面量、`AccountMutation.Create/Update/Delete(expectedRevision)`、五张设置表各自
  `Unchanged/ReplaceAll`、metadata expected-old/new CAS 和四个事务观察点。
- Produces: `WalletDatabase` version `1`，实体集合严格为 `accounts`、
  `mutation_operations`、`app_metadata`、`app_settings`、
  `account_alert_settings`、`notification_wallet_selections`、
  `alert_runtime_state`、`snooze_state`、`balance_records`、
  `daily_summaries`、`usage_snapshots`、`usage_records`、`event_logs`、
  `refresh_runs`、`refresh_account_results`、`download_operations`、
  `maintenance_checkpoint`、`monitoring_state`、`monitoring_sessions`。
- Produces: `suspend fun MutationPublisher.publish(input: MutationPublication): PublicationResult`；
  成功返回 `PublicationResult(operationId, baselineRevision + 1)`，任何账户 CAS、metadata
  CAS、DAO 写入或观察点失败都在同一 `withTransaction` 内回滚。
- Later tasks may add `suspend`/`Flow` DAO queries only. They may not change v1
  identity. No Task 2 class is registered in `DeepSeekApp` or called by现有 repository/UI/service。

- [ ] 创建不接生产调用方的 schema/API 支持面：19 个 `@Entity`、canonical DAO
  最小签名、完整 converter/DTO 类型、`WalletDatabase` version 1，以及暂时抛出
  `UnsupportedOperationException("Room v1 publication is not implemented")` 的
  `MutationPublisher.publish`。此支持提交必须一次性包含全部 19 表，能够编译并打开
  Room；不得创建不完整 v1、不得修改 Application/Repository/UI/Service。
- [ ] 串行运行 `compileDebugKotlin` 和 `compileDebugUnitTestKotlin`，提交 schema/API
  支持面；报告中注明这不是 GREEN，发布事务仍不可用。
- [ ] 写行为级 RED：使用真实 in-memory Room 调用 `MutationPublisher.publish`，覆盖
  Create/Update/Delete revision CAS、五表独立 settings publication、metadata CAS、
  operation `PUBLISHED`，并分别在 `AFTER_ACCOUNT_ROWS`、`AFTER_SETTINGS_ROWS`、
  `AFTER_METADATA`、`AFTER_OPERATION_PUBLISHED` 抛错后用新事务验证所有 Room 行未变。
  RED 必须可编译，并因上述显式 `UnsupportedOperationException` 失败。
- [ ] 同一 RED 提交加入字面 schema/行为契约：PRAGMA 与导出 JSON 固定全部 19 表；
  未知 enum/provider 原始字面量经 DAO 读取必须失败；revision 单调递增；daily summary
  唯一；账户删除级联 owned rows 但保留 run-owned refresh result；下载 nullable active
  slot 唯一；第二个 `DATA_SYNC` 开放会话被拒绝，close/recovery 清 slot 后可重开；
  overlap closed/open 分支和 `ended_at <= effectiveCutoff` prune 使用真实 Room 行。
- [ ] 同一字面门禁固定 `HISTORY_DATA_IMPORT`、`manifest_version` 和 committed
  `batch_cursor`；`usage_records` 以 `(snapshot_id, record_ordinal)` 保留同名 model；
  event-log 金额/币种原始文本逐字节保留；`refresh_account_results.account_id` 不建立
  account FK，账户删除后 run-owned 结果仍可终结为 `ACCOUNT_STALE`。
- [ ] 运行 RED 命令并记录具体失败测试和异常；确认没有编译、fixture 或环境失败后，
  仅提交测试。
- [ ] 实现最小 GREEN：在一个 `RoomDatabase.withTransaction` 中依次校验 operation、
  执行 typed account CAS、逐表 settings replacement、revision/metadata CAS、标记
  `PUBLISHED`；外部凭据只作为 generation 引用，绝不伪装成随 Room 回滚。
- [ ] 完成 canonical converter 的 exhaustive literal map；未知值抛
  `IllegalArgumentException`，`ProviderType` 不得回退到 DeepSeek。完成 schema export，
  保持所有 DAO 为 `suspend` 或 `Flow`，不使用 `allowMainThreadQueries()`。
- [ ] 运行 GREEN 聚焦测试、Debug 编译和 `git diff --check`；提交 GREEN，报告记录
  RED/GREEN 命令、测试计数、四个提交范围（既有依赖支持/schema API 支持/RED/GREEN）、
  export 路径、未解决风险和自审结论。

```powershell
.\gradlew.bat compileDebugKotlin compileDebugUnitTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.local.WalletDatabaseTest" --tests "com.balancesentinel.app.data.local.DatabaseConvertersTest" --tests "com.balancesentinel.app.data.local.account.AccountDaoTest" --tests "com.balancesentinel.app.data.local.publication.MutationPublisherTest" --tests "com.balancesentinel.app.data.local.monitoring.MonitoringSessionDaoTest" --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
git diff --check
```

### Task 3：可重入账户引导迁移与稳定 UUID

**文件：**

- Create: `data/migration/LegacyAccountMigration.kt`, `LegacyAccountMigrationStage.kt`, `LegacyAccountMapping.kt`
- Create: `data/repository/AccountRepository.kt`, `AccountMapper.kt`
- Modify: `data/repository/ApiKeyManager.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/DeepSeekApp.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/migration/LegacyAccountMigrationTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/repository/AccountRepositoryTest.kt`

- [ ] 先加入 migration stage/model、repository 接口和转发旧 `ApiKeyManager` 的无行为 adapter，完成 support commit；此时启动行为不得改变。
- [ ] 通过现有 `DeepSeekApp`/`ApiKeyManager` 入口写 RED：同一旧账户重复迁移 ID 稳定；API Key 改变后 UUID 不变；坏旧 JSON 不创建空 Room 状态。
- [ ] 对 DISCOVERED、VALIDATED、CREDENTIALS_STAGED、ROOM_WRITTEN、VERIFIED 每阶段注入崩溃，验证重跑无重复且旧数据仍在。
- [ ] 提交 RED。
- [ ] 实现账户元数据、凭据 generation、`legacyStorageId` 和阶段台账迁移；本任务不删旧 JSON、不迁历史、不宣布全局 CLEANED。
- [ ] staged 凭据必须读回；只有 VERIFIED 账户可由新 repository 返回。
- [ ] 运行聚焦测试和启动迁移回归，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyAccountMigrationTest" --tests "com.balancesentinel.app.data.repository.AccountRepositoryTest" --rerun-tasks --no-parallel
```

### Task 4：账户生命周期协调器与恢复核心

**核心文件：**

- Create: `data/repository/AccountMutationCoordinator.kt`, `AccountMutationRecovery.kt`, `AccountMutationResult.kt`
- Modify: `data/repository/AccountLifecycleManager.kt`, `ApiKeyManager.kt`, `DataMutationCoordinator.kt`
- Modify: `DeepSeekApp.kt`
- Modify: `AccountLifecycleManagerTest.kt`
- Create: `AccountMutationRecoveryTest.kt`

- [ ] 先加入 coordinator/result/recovery 接口和委托旧实现的 adapter，完成 support commit。
- [ ] 写 RED：分别在 stage、read-back、Room publish、旧凭据 cleanup 注入异常。
- [ ] 写 RED：在多账户第 N 个加密偏好 commit 后模拟进程死亡；恢复器必须根据持久 manifest 找到、验证或删除全部确定性 generation key。
- [ ] 验证发布前只见完整旧账户，发布后只见完整新账户；cleanup 失败重启后幂等完成。
- [ ] 验证删除先原子隐藏并级联，旧 prefs/cache 清理失败不复活；编辑 Key 不迁移历史 ID。
- [ ] 验证 active generation 损坏时所有写操作停止。
- [ ] 提交 RED。
- [ ] 实现 suspend/Flow repository 和恢复器；旧 Widget/cache/prefs 仅作为发布后 orphan cleanup。本任务不迁移 UI、Service 或 Widget 调用方。
- [ ] 运行核心聚焦测试和 Debug 编译，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --tests "com.balancesentinel.app.data.repository.AccountMutationRecoveryTest" --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

### Task 5：账户 Repository 的界面调用面切换

**文件：**

- Modify: `HomeViewModel.kt`, `DataManagementViewModel.kt`, `InsightsViewModel.kt`
- Modify: `BackupRestoreScreen.kt`, `AlertSettingsScreen.kt`, `HomeScreen.kt`
- Modify corresponding ViewModel and screen tests

- [ ] 通过现有 ViewModel 写 RED：账户 Flow 异步加载，损坏状态明确显示且不触发写；创建、编辑、删除只调用新 coordinator 一次。
- [ ] 验证主线程不查询 Room，页面重建重新订阅 repository，不保留旧 `AccountInfo` 对象。
- [ ] 提交 RED。
- [ ] 切换 UI 调用面；禁止 `runBlocking` 或默认空列表兼容层。
- [ ] 运行 UI/ViewModel 聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest" --rerun-tasks --no-parallel
```

### Task 6：账户 Repository 的服务、刷新和 Widget 调用面切换

**文件：**

- Modify: `RefreshModels.kt`, `RefreshRuntime.kt`, `RefreshCoordinator.kt`
- Modify: `BalanceRefreshService.kt`, `StaticWidgetProvider.kt`, `WidgetConfigActivity.kt`
- Modify corresponding refresh、service、widget tests

- [ ] 通过现有刷新、Service 和 Widget 入口写 RED：稳定 UUID/revision 被消费，损坏账户不刷新，BroadcastReceiver 使用 `goAsync` 完成异步读取。
- [ ] 验证账户删除/编辑期间的旧请求不能提交，且调用面不阻塞主线程。
- [ ] 提交 RED。
- [ ] 切换非 UI 调用面到 repository；不改变 Task 14 以后定义的批次结果语义。
- [ ] 运行聚焦测试和 Debug 编译，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshCoordinatorTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.widget.WidgetProviderTest" --rerun-tasks --no-parallel
```

### Task 7：Room 设置单一真源与旧间隔迁移

**文件：**

- Create: `data/repository/SettingsRepository.kt`, `RoomSettingsRepository.kt`, `SettingsSnapshot.kt`, `LegacySettingsMigration.kt`
- Complete: `data/local/settings/SettingsDao.kt`
- Modify all ConfigSettings/account-setting consumers: `ConfigManager.kt`, `WidgetPrefs.kt`, `AlertChecker.kt`, `RefreshLogStore.kt`, `DeepSeekApp.kt`, `SnoozeReceiver.kt`, `BalanceNotificationDeriver.kt`, `BalanceRefreshService.kt`, `AlertSettingsScreen.kt`, `BackupRestoreScreen.kt`, `SettingsScreen.kt`, `DataManagementViewModel.kt`, `HomeViewModel.kt`, `LogViewModel.kt`, `StaticWidgetProvider.kt`, `AccountLifecycleManager.kt`, `BackupImportPlanner.kt`
- Create/modify SettingsRepository、migration and consumer tests

**所有权：** 语言、onboarding、权限请求历史、更新偏好和单个 Widget 实例布局为设备偏好，不参与配置导入。全局刷新/告警、逐账户告警、通知选择、告警锚点和 snooze 以 Room 为唯一真源，禁止双写。

- [ ] 先加入 repository/model/Flow 和转发旧 `WidgetPrefs` 的无行为 adapter，完成 support commit。
- [ ] 通过现有 Settings、Service、Alert、Receiver 入口写 RED：Room transaction 发布后所有消费者观察同一 snapshot，崩溃时不存在 Room/prefs 混合有效状态。
- [ ] 写 899/900 秒边界 RED：旧值小于 900 秒迁移为前台会话周期，后台周期设 900 秒；旧值大于等于 900 秒可作为后台周期。
- [ ] 验证导入和 UI 区分 `backgroundRefreshInterval` 与 `foregroundMonitoringInterval`，展示 effective cadence。
- [ ] 提交 RED。
- [ ] 将调用方迁到 StateFlow/挂起 API；需要同步渲染的代码只读已发布 immutable snapshot，未加载时显示 Loading，不查主线程数据库。
- [ ] 静态搜索确保导入设置和账户设置不再直接读写 `WidgetPrefs`，运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --rerun-tasks --no-parallel
```

### Task 8：有界配置解析、陈旧预览与原子导入

**文件：**

- Create: `data/io/BoundedInput.kt`
- Create: `data/repository/ConfigImportParser.kt`, `ImportFingerprint.kt`, `ImportCoordinator.kt`
- Modify: `ConfigManager.kt`, `BackupImportPlanner.kt`, `DataManagementViewModel.kt`, `SettingsRepository.kt`
- Modify: `ConfigManagerTest.kt`, `BackupImportPlannerTest.kt`, `DataManagementViewModelTest.kt`
- Create: `ConfigImportParserTest.kt`, `ImportCoordinatorTest.kt`

**固定限制：** 配置 4 MiB、最多 256 个账户、普通字段 16 KiB、脚本字段 256 KiB、JSON 深度 32。

- [ ] 为每项上限写边界值成功和 `+1` 失败测试，验证账户、设置、修订号和凭据均无副作用。
- [ ] 写 RED：预览后修改任一账户或相关设置，应用返回 `StalePlan`；重新预览后可应用。
- [ ] 写 RED：备份 revision 不覆盖本地 revision；多账户第 N 个 stage commit、任一 read-back 或 publish 失败都根据 manifest 保持完整旧状态。
- [ ] 提交 RED。
- [ ] 实现有界读取、canonical plan SHA-256、baselineRevision 和一次 Room 事务发布账户、`app_settings`、localRevision。
- [ ] 本任务只处理配置文件；历史大文件流式处理留到 Task 13。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.ConfigImportParserTest" --tests "com.balancesentinel.app.data.repository.BackupImportPlannerTest" --tests "com.balancesentinel.app.data.repository.ImportCoordinatorTest" --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --rerun-tasks --no-parallel
```

### 阶段 1 门禁

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
.\gradlew.bat lintDebug --rerun-tasks --no-parallel
$ledger = '.superpowers\sdd\2026-08-05-wallet-sentinel-remediation\progress.md'
$phase1Base = (Select-String -LiteralPath $ledger -Pattern '^Phase 1 base: ([0-9a-f]{40})$').Matches[0].Groups[1].Value
if ([string]::IsNullOrWhiteSpace($phase1Base)) { throw 'PHASE_1_BASE_MISSING' }
git diff --check "${phase1Base}..HEAD"
```

门禁通过且阶段审查无 Critical/Important 后才能进入阶段 2。

---

## 阶段 2：Room 历史存储迁移

进入本阶段前，在 ledger 写入 `Phase 2 base: <git rev-parse HEAD 的完整输出>`。

### Task 9：历史、摘要、用量和日志领域 Repository

**文件：**

- Create: `data/repository/HistoryRepository.kt`, `UsageRepository.kt`, `EventLogRepository.kt`
- Create: `data/repository/HistoryPage.kt`, `HistoryAggregate.kt`
- Complete: `data/local/history/HistoryDao.kt`, `data/local/usage/UsageDao.kt`, `data/local/log/EventLogDao.kt`
- Modify mapping only: `DailySummary.kt`, `UsageRecord.kt`, `RefreshLogEntry.kt`
- Create tests: `HistoryDaoTest.kt`, `HistoryRepositoryTest.kt`, `UsageRepositoryTest.kt`, `EventLogRepositoryTest.kt`

- [ ] 先加入 repository/domain 接口和委托旧 stores 的无行为 adapter，完成 support commit。
- [ ] 通过现有 Insights/Cleanup/DataExporter 入口写 RED：keyset page `(recordedAt,id)` 固定最大 200 条，首中末页无重复或缺失。
- [ ] 验证范围、账户、币种过滤，未知 ISO 拒绝，摘要唯一键，用量子记录完整，日志 newest-first 和 limit。
- [ ] 以 90,000 行验证所有页面不超过 200；SQL 聚合与现有 `RecordAggregator` 语义一致。
- [ ] 提交 RED。
- [ ] 实现 suspend DAO、分页、范围、COUNT、DISTINCT 和数据库聚合；批量 insert chunk 固定 500。
- [ ] 不暴露生产 `getAllRecords()`；领域 model 不加 Room 注解。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.local.history.HistoryDaoTest" --tests "com.balancesentinel.app.data.repository.HistoryRepositoryTest" --tests "com.balancesentinel.app.data.repository.UsageRepositoryTest" --tests "com.balancesentinel.app.data.repository.EventLogRepositoryTest" --rerun-tasks --no-parallel
```

### Task 10：完整旧数据可重入迁移

**文件：**

- Create: `data/migration/LegacyDataSource.kt`, `LegacyDataMigration.kt`, `LegacyDataVerifier.kt`
- Modify: `LegacyAccountMigration.kt`, `DeepSeekApp.kt`
- Create: `LegacyDataMigrationTest.kt`, `LegacyDataMigrationLargeDatasetTest.kt`

- [ ] 先加入 data source/verifier 接口和委托旧 stores 的无行为 migration seam，完成 support commit。
- [ ] 通过现有 `DeepSeekApp` 启动迁移入口，为 DISCOVERED、VALIDATED、CREDENTIALS_STAGED、ROOM_WRITTEN、VERIFIED、ACTIVE、CLEANED 各阶段写故障恢复 RED。
- [ ] 验证 90,000 records 按 500 批处理，旧 accountId 通过 mapping 写稳定 UUID。
- [ ] 验证记录、摘要、用量、日志计数和关键字段；任一旧 JSON 损坏或写失败都不 ACTIVE、不清旧值。
- [ ] 验证重复执行不重复；只有 VERIFIED 后切 ACTIVE，只有 ACTIVE 后清 prefs；cleanup 失败仍可从 Room 完整读取。
- [ ] 提交 RED。
- [ ] 实现持久 operation、阶段和 batch cursor，ACTIVE 只通过 metadata 单事务切换，CLEANED 延迟。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationLargeDatasetTest" --rerun-tasks --no-parallel
```

### Task 11：刷新提交、清理和账户级联切换 Room

**修改：**

- `RefreshResultCommitter.kt`, `CleanupScheduler.kt`, `AccountLifecycleManager.kt`, `DeepSeekApp.kt`
- 将 `RawRecordStore.kt`, `DailySummaryStore.kt`, `UsageDataStore.kt`, `RefreshLogStore.kt` 降为 legacy migration reader，删除生产调用。
- Modify tests: `RefreshResultCommitterTest.kt`, `CleanupSchedulerTest.kt`, `AccountLifecycleManagerTest.kt`

- [ ] 写 RED：一次 refresh 的 records/usage/logs 在同一 Room 事务全成或全败。
- [ ] 写 RED：cleanup 事务写摘要后删除已归档 raw。
- [ ] 写 RED：删除账户依赖 FK cascade，不扫描或重写 90,000 行。
- [ ] 提交 RED。
- [ ] 替换写入、cleanup 和 FK 级联调用，不在本任务重构阶段 3 的刷新结果类型。
- [ ] 移除这些路径的 snapshot/restore 和 SharedPreferences 回滚。
- [ ] 运行聚焦测试、全仓搜索旧调用并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --rerun-tasks --no-parallel
```

### Task 12：查询消费者切换 Room

**文件：**

- Modify: `InsightsViewModel.kt`, `DataManagementViewModel.kt`, `StaticWidgetProvider.kt`, `LogViewModel.kt`, `LogExporter.kt`, `DataExporter.kt`
- Modify corresponding ViewModel、Widget、export tests

- [ ] 写 RED：数据管理冷启动只用 COUNT/DISTINCT 和 page0；Insights 只查当前范围；Widget 只查摘要；日志使用 limit/page。
- [ ] 验证任何消费者都不调用 legacy getAll，也不把 90,000 行保存在 UI state。
- [ ] 提交 RED。
- [ ] 切换查询调用面并移除生产 getAll；BroadcastReceiver 使用 `goAsync` 或 WorkManager。
- [ ] 运行聚焦测试和静态旧调用搜索，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --tests "com.balancesentinel.app.widget.WidgetProviderTest" --tests "com.balancesentinel.app.ui.viewmodel.LogViewModelTest" --rerun-tasks --no-parallel
```

### Task 13：90k 历史流式导入导出

**文件：**

- Create: `data/io/HistoryJsonReader.kt`, `HistoryJsonWriter.kt`
- Modify: `DataExporter.kt`, `DataManagementViewModel.kt`
- Modify: `DataExporterTest.kt`, `DataExporterImportTest.kt`
- Create: `HistoryStreamingLargeDatasetTest.kt`
- Create: `app/src/androidTest/java/com/balancesentinel/app/data/local/WalletDatabaseMigrationTest.kt`

**固定限制：** 文件 256 MiB、raw 100,000、summary 50,000、usage 10,000、logs 10,000、单字段 256 KiB、深度 32、page/chunk 500。

- [ ] 写 RED：90k export 使用分页且可重导；90k import 使用固定批次，不构造全量 `DataExport`。
- [ ] 为每个大小/记录数/字段/深度限制写边界和 `+1`，验证发布前失败无副作用。
- [ ] 验证中途异常不 ACTIVE、重复导入幂等、冷启动统计使用 SQL。
- [ ] 提交 RED。
- [ ] 使用 Android `JsonReader/JsonWriter` 实现真实流式读写和最终发布事务。
- [ ] 增加首个 schema identity/exported schema 仪器门禁。
- [ ] 运行 JVM、编译和设备聚焦测试后提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.DataExporterTest" --tests "com.balancesentinel.app.data.repository.DataExporterImportTest" --tests "com.balancesentinel.app.data.repository.HistoryStreamingLargeDatasetTest" --rerun-tasks --no-parallel
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.data.local.WalletDatabaseMigrationTest --no-parallel
```

### 阶段 2 门禁

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat lintDebug --rerun-tasks --no-parallel
$ledger = '.superpowers\sdd\2026-08-05-wallet-sentinel-remediation\progress.md'
$phase2Base = (Select-String -LiteralPath $ledger -Pattern '^Phase 2 base: ([0-9a-f]{40})$').Matches[0].Groups[1].Value
if ([string]::IsNullOrWhiteSpace($phase2Base)) { throw 'PHASE_2_BASE_MISSING' }
git diff --check "${phase2Base}..HEAD"
```

---

## 阶段 3：类型化刷新、网络边界与后台调度

进入本阶段前，在 ledger 写入 `Phase 3 base: <git rev-parse HEAD 的完整输出>`。

### Task 14：批次刷新结果、运行台账与旧缓存语义

**文件：**

- Modify: `data/refresh/RefreshModels.kt`, `AccountBalanceRefresher.kt`, `RefreshCoordinator.kt`, `RefreshResultCommitter.kt`, `RefreshRuntime.kt`
- Modify: `service/BalanceRefreshRunner.kt`, `BalanceRefreshService.kt`
- Modify: `widget/WidgetRefreshRunner.kt`, `StaticWidgetProvider.kt`
- Create: `data/refresh/RefreshBatchResult.kt`, `RefreshRunRecorder.kt`, `RoomRefreshRunRecorder.kt`
- Modify tests: `AccountBalanceRefresherTest.kt`, `RefreshCoordinatorTest.kt`, `RefreshResultCommitterTest.kt`, `BalanceRefreshRunnerTest.kt`, `widget/BalanceRefreshRunnerTest.kt`
- Create: `RoomRefreshRunRecorderTest.kt`

- [ ] 写 RED：全成功、部分成功、全失败保留每个 accountId 结果和 aggregate。
- [ ] 验证认证/解析/策略不可重试，网络/限流携带 retryable/ retryAfter；取消记录 Cancelled 后重新抛 `CancellationException`。
- [ ] 写 RED：旧缓存存在时失败仍是失败，并携带 `stale=true`、原数据时间和本次原因；不得 `recordSuccess`。
- [ ] 写 RED：每账户 records、usage、event log 和 `refresh_account_results` 终态必须同一 Room 事务；在业务数据侧和结果侧分别注入失败都不得留下半提交。
- [ ] 写 RED：启动恢复把没有活动 owner 的 RUNNING 标为 Interrupted；batch aggregate 只能由已提交的逐项终态派生。
- [ ] 提交 RED。
- [ ] 让 `refreshAll` 返回 `RefreshBatchResult`，生成 runId 并逐项写 Room。
- [ ] Service 与 Widget 消费真实 aggregate 和 stale projection，不再丢弃结果。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.*" --tests "com.balancesentinel.app.service.BalanceRefreshRunnerTest" --tests "com.balancesentinel.app.widget.BalanceRefreshRunnerTest" --rerun-tasks --no-parallel
```

### Task 15：共享有界响应读取与取消传播

**文件：**

- Create: `data/network/ResponseBudget.kt`, `BoundedResponseReader.kt`, `EncodedResponseLimitInterceptor.kt`, `NetworkResponseException.kt`
- Modify: `DeepSeekApiService.kt`, `DeepSeekProvider.kt`, `BalanceQueryService.kt`, `UsageScriptExecutor.kt`, `UpdateChecker.kt`, `ui/console/ConsoleScreen.kt`
- Create tests: `BoundedResponseReaderTest.kt`, `EncodedResponseLimitInterceptorTest.kt`
- Modify relevant API、script、update、Console response tests

- [ ] 写 RED：已知 Content-Length 超限在读流前失败；chunked 在 max+1 停止并关闭 body。
- [ ] 写 RED：压缩和解压预算分别生效，gzip bomb、错误 Content-Type、非 2xx 完整 body、取消均安全失败。
- [ ] 验证 Console 超限不截断后伪造成功响应。
- [ ] 提交 RED。
- [ ] 实现压缩字节 interceptor 和解压字节 reader，按 endpoint 配置预算并返回稳定异常。
- [ ] 移除所有文本/JSON `body.string()` 和相关 `readBytes()`；APK 流式写留 Task 24。
- [ ] 运行聚焦测试和 `rg` 静态门禁，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.network.*" --tests "com.balancesentinel.app.data.api.DeepSeekApiServiceTest" --tests "com.balancesentinel.app.data.api.balance.BalanceQueryServiceTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --tests "com.balancesentinel.app.data.update.UpdateCheckerIntegrationTest" --tests "com.balancesentinel.app.ui.console.ConsoleResponseInterceptionTest" --rerun-tasks --no-parallel
```

### Task 16：WorkManager 普通刷新和有限重试

**文件：**

- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `DeepSeekApp.kt`
- Modify: `widget/StaticWidgetProvider.kt`, `WidgetRefreshDispatcher.kt`, `WidgetConfigStore.kt`
- Create: `work/RefreshWorker.kt`, `RefreshWorkScheduler.kt`, `RefreshRetryPlanner.kt`, `WorkRuntime.kt`
- Create tests: `RefreshWorkerTest.kt`, `RefreshWorkSchedulerTest.kt`, `RefreshRetryPlannerTest.kt`

- [ ] 先引入 Work runtime/testing 2.10.1 和 scheduler 接口，作为不改变行为的支持提交。
- [ ] 写 RED：重复 bootstrap 或改间隔后只有一个稳定名称 periodic work，且带网络约束。
- [ ] 写 RED：partial 只重试 retryable 失败账户，成功和永久失败不重刷；退避和 jitter 有上限。
- [ ] 验证进程重建只 reconcile、不重复 work。
- [ ] 写 RED：升级后取消所有旧 Widget Alarm PendingIntent；Widget 周期刷新只使用统一 work，手动点击仍可即时触发，不保留平行 Alarm 链。
- [ ] 提交 RED。
- [ ] 实现 `CoroutineWorker` 调统一引擎；periodic 使用 UPDATE，失败账户 one-shot 使用稳定唯一名和有限 attempt。小于 15 分钟的配置只在活动前台会话内生效。
- [ ] 将 Widget 调度接入统一 scheduler，并在首次 reconcile 取消旧 alarm requestCode。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.work.Refresh*" --rerun-tasks --no-parallel
```

### Task 17：可重入午夜补做与恢复入口

**文件：**

- Create: `work/MidnightMaintenanceWorker.kt`, `MidnightWorkScheduler.kt`, `MaintenanceCheckpointStore.kt`
- Create: `receiver/WorkReconcileReceiver.kt`
- Modify: `CleanupScheduler.kt`, `DeepSeekApp.kt`, `AndroidManifest.xml`, `BootReceiver.kt`
- Retire: `MidnightScheduler.kt`, `MidnightReceiver.kt` 及旧 Alarm 声明
- Create/modify corresponding work、receiver、cleanup tests

- [ ] 先加入 worker/scheduler/checkpoint 接口并让现有 `MidnightScheduler`/`BootReceiver` 转发旧行为，完成 support commit。
- [ ] 通过现有午夜和启动入口写 RED：延迟三天按日期顺序各执行一次；第二天失败只推进第一天，重试从第二天继续。
- [ ] 验证完成后按当前 ZoneId 计算下一本地午夜，DST/时区变化不漂移。
- [ ] 验证 boot、MY_PACKAGE_REPLACED、TIMEZONE_CHANGED 只 reconcile 唯一 work；不打开首页也存在任务。
- [ ] 提交 RED。
- [ ] 实现一次性唯一 WorkRequest、按日期幂等 checkpoint 和每次完成后重挂。
- [ ] Receiver 不直接清理、不启动普通服务；退役旧午夜 Alarm 链。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.work.Midnight*" --tests "com.balancesentinel.app.receiver.WorkReconcileReceiverTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --rerun-tasks --no-parallel
```

### Task 18：有界前台监控会话与存活租约

**文件：**

- Create: `service/MonitoringStateStore.kt`, `ServiceLease.kt`, `ServiceLeaseEvaluator.kt`, `ContinuousMonitoringController.kt`, `MonitoringBudgetCalculator.kt`
- Create: `work/MonitoringHealthWorker.kt`
- Modify: `data/local/monitoring/MonitoringSessionDao.kt`, `MonitoringStateDao.kt`
- Modify: `BalanceRefreshService.kt`, `ForegroundServiceStarter.kt`, `RefreshScheduler.kt`, `MainActivity.kt`, `HomeViewModel.kt`, `DeepSeekApp.kt`, `BootReceiver.kt`, `AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-en/strings.xml`
- Retire: `KeepAliveReceiver.kt` 及精确 Alarm 健康链
- Create: `app/src/test/java/com/balancesentinel/app/service/MonitoringBudgetCalculatorTest.kt`
- Create/modify: `app/src/test/java/com/balancesentinel/app/service/ContinuousMonitoringControllerTest.kt`, `app/src/test/java/com/balancesentinel/app/work/MonitoringHealthWorkerTest.kt`, `app/src/test/java/com/balancesentinel/app/data/repository/RefreshSchedulerTest.kt`, `app/src/test/java/com/balancesentinel/app/receiver/BootReceiverTest.kt`

- [ ] 写 RED：desired=false 即使旧心跳存在也 stopped；desired=true 但本进程无新鲜租约只能 starting/abnormal。
- [ ] 验证旧 processSessionId 心跳立即失效，租约过期 running 转 abnormal。
- [ ] 验证服务存活期不调用 DETACH，onDestroy 只清当前 session；boot 不无条件启动 FGS。
- [ ] 写 RED：Android 15 `dataSync` 滚动 24 小时预算由 Room `monitoring_sessions`
  的半开区间并集重建；重复、重叠、相邻、跨 cutoff、恰好结束于 cutoff、恰好开始于
  now、开放会话和用户前台 reset 均不得重复计时或获得第二份预算。
- [ ] 验证 `effectiveCutoff = min(now, max(now - 86_400_000,
  lastUserForegroundResetAt ?: Long.MIN_VALUE))`；纯 `MonitoringBudgetCalculator` 只负责
  clip/sort/merge/sum，DAO 只返回 closed/open overlap candidates，不在 SQL 中求和。
- [ ] 验证 start 事务同时插入 `active_slot = 'DATA_SYNC'` 会话并更新 projection；
  close/recovery 事务同时写 `ended_at`、清 active slot 并更新 projection；第二个开放会话
  被唯一索引拒绝，旧进程开放会话以 `PROCESS_RECOVERY` 保守结束后才能重开。
- [ ] 写 RED：会话在平台 6 小时/24 小时限制前主动结束；系统 `onTimeout` 后立即
  stopSelf、移除前台并进入 PlatformLimited/Paused，且不得自动重启。只删除
  `ended_at <= effectiveCutoff` 的 closed rows，跨 cutoff 行必须保留。
- [ ] 验证会话受限后普通 WorkManager 刷新仍存在，小于 15 分钟的期望间隔显示为已降级而不是运行中。
- [ ] 提交 RED。
- [ ] Application 生成 processSessionId；Service 用可取消 coroutine loop 调统一引擎并始终保持前台通知；会话是有界、用户发起的 dataSync 会话，不承诺永久常驻。
- [ ] 普通后台完全交给 WorkManager，MainActivity 首启不自动启动服务；取消旧 KeepAlive、Service retry 和 Widget Alarm，删除 `RefreshScheduler` 旧诊断，更新两语言隐私说明，并移除 `SCHEDULE_EXACT_ALARM` 权限。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.service.*" --tests "com.balancesentinel.app.work.MonitoringHealthWorkerTest" --tests "com.balancesentinel.app.data.repository.RefreshSchedulerTest" --tests "com.balancesentinel.app.receiver.BootReceiverTest" --rerun-tasks --no-parallel
```

### Task 19：DeepSeek 当前和备用证书 pin

**文件：**

- Create: `data/network/DeepSeekTlsPolicy.kt`
- Modify: `DeepSeekApiService.kt`, `DeepSeekProvider.kt`, `BalanceQueryService.kt`
- Modify: `app/src/main/res/xml/network_security_config.xml`
- Create: `DeepSeekTlsPolicyTest.kt`, `NetworkSecurityConfigPinParityTest.kt`

- [ ] 从可信实际证书链提取当前 leaf SPKI 和备用 issuer/rotation SPKI，记录 subject、issuer、有效期和复核命令。
- [ ] 先加入 `DeepSeekTlsPolicy` 接口和复用现有单 pin 的无行为 adapter，完成 support commit。
- [ ] 保存可信导出的 current/backup X.509 DER 公钥证书夹具；直接调用 `CertificatePinner.check(host, certChain)` 写 RED，验证 current/backup 分别通过、第三证书失败且绝不降级。
- [ ] 另用生成的 HeldCertificate 做通用 OkHttp 握手成功/失败集成测试；在线 current probe 只作非阻断复核，不要求掌握备用证书私钥。
- [ ] 验证生产 pin 至少两个、互异、解码 32 字节，XML 和 Kotlin 完全一致。
- [ ] 提交 RED。
- [ ] 对 `api.deepseek.com` 同时配置当前和备用 pin，其他主机不误用；失败映射为明确 TLS/网络错误。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.network.DeepSeekTlsPolicyTest" --tests "com.balancesentinel.app.data.network.NetworkSecurityConfigPinParityTest" --tests "com.balancesentinel.app.data.api.balance.BalanceQueryServiceTest" --rerun-tasks --no-parallel
```

### 阶段 3 门禁

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat lintDebug --rerun-tasks --no-parallel
adb devices -l
$ledger = '.superpowers\sdd\2026-08-05-wallet-sentinel-remediation\progress.md'
$phase3Base = (Select-String -LiteralPath $ledger -Pattern '^Phase 3 base: ([0-9a-f]{40})$').Matches[0].Groups[1].Value
if ([string]::IsNullOrWhiteSpace($phase3Base)) { throw 'PHASE_3_BASE_MISSING' }
git diff --check "${phase3Base}..HEAD"
rg -n "SCHEDULE_EXACT_ALARM|setAlarmClock|setExactAndAllowWhileIdle|STOP_FOREGROUND_DETACH" app/src/main
```

上述 `rg` 必须无匹配。设备上验证普通 work 在 service 停止时仍登记、desired=false 无 FGS、活动监控会话始终在前台、预算受限后状态明确且普通 work 仍登记。无新 Critical/Important 后进入阶段 4。

---

## 阶段 4：导航、界面状态、权限、下载与签名

进入本阶段前，在 ledger 写入 `Phase 4 base: <git rev-parse HEAD 的完整输出>`。

### Task 20：类型化路由、深链解析和导航支持层

**文件：**

- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`
- Modify: `NotificationHelper.kt`, `StaticWidgetProvider.kt`
- Create: `ui/navigation/AppRoute.kt`, `DeepLinkResolver.kt`
- Create: `DeepLinkResolverTest.kt`; modify `NotificationHelperTest.kt`

**路由：** Onboarding、Home、Insights、Settings、RefreshSettings、SystemStatus、About、Log、DataHub、ClearData、BackupRestore、AlertSettings、ConsoleSelect、Console(platformId)、AddPlatform、InvalidDeepLink。

- [ ] 引入 Navigation Compose/testing 2.8.9、route model、resolver 接口和旧导航 adapter，完成无行为 support commit。
- [ ] 写 RED：合法 account/currency 深链精确选中；无效、缺失、已删账户或非 ISO currency 进入 InvalidDeepLink。
- [ ] 验证 Widget/通知 URI 唯一且规范化；旧 extras 只能进入同一 resolver。
- [ ] 提交 RED。
- [ ] 实现纯 resolver 和类型化 route；本任务不迁移屏幕或 `MainActivity`。
- [ ] 运行 JVM 聚焦测试，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.navigation.DeepLinkResolverTest" --tests "com.balancesentinel.app.data.repository.NotificationHelperTest" --rerun-tasks --no-parallel
```

### Task 21：单一 NavHost 和屏幕迁移恢复

**文件：**

- Create: `ui/navigation/WalletNavHost.kt`, `InvalidDeepLinkScreen.kt`
- Modify: `MainActivity.kt`, `SettingsScreen.kt`, `DataManagementScreen.kt`, `InsightsViewModel.kt`
- Create: `WalletNavHostTest.kt`; modify `MainActivityTest.kt` 和相关 screen tests

- [ ] 通过现有 MainActivity 写 RED：Home→Settings→About 的系统返回栈；recreate 后仍在 About，筛选 ID 恢复但业务对象重载。
- [ ] 验证 Console 只保存 platformId，已删平台进入 InvalidDeepLink；tab 使用 save/restore 和 singleTop。
- [ ] 提交 RED。
- [ ] 实现唯一 `NavHost` 并移除 Settings/DataManagement 内部枚举路由；不改变页面视觉。
- [ ] 运行 JVM、AndroidTest 编译和设备导航测试，提交 GREEN。

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.navigation.WalletNavHostTest --no-parallel
```

### Task 22：首页逐账户刷新状态和旧完成隔离

**文件：**

- Create: `ui/viewmodel/AccountRefreshUiState.kt`, `HomeUiEvent.kt`
- Modify: `HomeViewModel.kt`, `HomeScreen.kt`, `AccountBalanceCard.kt`, `HomeViewModelTest.kt`, `HomeScreenTest.kt`
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/screen/HomeRefreshStateTest.kt`

- [ ] 用可控 deferred 写 RED：A-old、A-new、B 乱序完成，A-old 不覆盖 A-new 或清 loading，B 不受影响。
- [ ] 验证失败保留余额和 dataTimestamp，stale=true，lastSuccessAt 不前移；部分成功逐账户隔离。
- [ ] 验证一次性事件在 collector 重建后不重复；UI 只有目标卡片 loading/error/stale。
- [ ] 提交 RED。
- [ ] 实现 `refreshStates: Map<accountId, state>` 和递增 requestId；全局 loading 只派生 `any`。
- [ ] 使用 Channel/Flow 发送一次性事件，不再以全局 lastRefreshTime/error 代表账户事实。
- [ ] 运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.screen.HomeRefreshStateTest --no-parallel
```

### Task 23：上下文权限与真实能力状态

**文件：**

- Create: `platform/permission/AppCapability.kt`, `AndroidCapabilityChecker.kt`
- Create: `ui/viewmodel/CapabilityViewModel.kt`, `CapabilityUiState.kt`
- Modify: `MainActivity.kt`, `AndroidManifest.xml`, `AlertSettingsScreen.kt`, `SettingsScreen.kt`, `WidgetPrefs.kt`
- Create/modify capability、viewmodel、MainActivity、Settings device tests

- [ ] 写 RED：首次启动和浏览设置不请求权限、不启动服务；用户 enable 后才请求。
- [ ] 验证拒绝后显示未授予且不调度，永久拒绝提供系统设置入口，重新授权按 desired 恢复且只调度一次。
- [ ] 验证通知、前台服务和 dataSync 会话预算分别呈现，不被单一 boolean 合并；升级后精确闹钟应显示为“不再需要”，而不是继续申请权限。
- [ ] 提交 RED。
- [ ] 实现 desired/effective 分离和上下文事件；删除 MainActivity onCreate 自动请求/启动。
- [ ] 运行 JVM 和设备聚焦测试，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.platform.permission.*" --tests "com.balancesentinel.app.ui.viewmodel.CapabilityViewModelTest" --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.screen.PermissionFlowTest --no-parallel
```

### Task 24：可取消、可恢复、单写者 APK 下载

**文件：**

- Create: `data/update/DownloadModels.kt`, `DownloadOperationStore.kt`, `ApkDownloadRepository.kt`, `ApkDownloadWorker.kt`
- Create: `ui/viewmodel/UpdateDownloadViewModel.kt`
- Modify: `ApkDownloader.kt`, `UpdateDialog.kt`, `MainActivity.kt`, `AndroidManifest.xml`
- Create/modify download repository、worker、viewmodel、dialog tests

- [ ] 写 RED：阻塞响应取消后协程和 Call 终止，只删除当前 UUID `.part`，其他操作和已发布 APK 保留。
- [ ] 验证取消后立即重试不并发写同路径；截断/非 APK 不发布；有效 APK 原子发布。
- [ ] 验证 VM 重建读取真实 Running/Failed/Completed，旧 operation 迟到不覆盖新 operation。
- [ ] 提交 RED。
- [ ] 使用唯一 work `apk-download:<tag>`、独立 UUID part、单活动 owner 和 Room 状态。
- [ ] 取消传播到 WorkManager、协程、OkHttp；校验长度和 ZIP 中 `AndroidManifest.xml` 后 fsync/close/同目录原子移动。
- [ ] Dialog 只渲染 ViewModel StateFlow；运行聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.update.ApkDownloaderIntegrationTest" --tests "com.balancesentinel.app.data.update.ApkDownloadRepositoryTest" --tests "com.balancesentinel.app.data.update.ApkDownloadWorkerTest" --tests "com.balancesentinel.app.ui.viewmodel.UpdateDownloadViewModelTest" --rerun-tasks --no-parallel
```

### Task 25：数据管理冷启动只读摘要和可取消长操作

**文件：**

- Create: `data/repository/DataStatisticsRepository.kt`, `RoomDataStatisticsRepository.kt`
- Create: `ui/viewmodel/DataOperationState.kt`
- Modify: `DataManagementViewModel.kt`, `DataManagementScreen.kt`, `BackupRestoreScreen.kt`
- Create/modify repository、viewmodel、cold-start device tests

- [ ] 写 RED：构造 VM 时 fullScan/getAll spy 会抛错，init 只能订阅摘要或执行 COUNT，且主线程不访问 90k 行。
- [ ] 验证首屏只取摘要和 page0；export/validate 携带 operationId/progress，可取消且取消不发成功。
- [ ] 验证破坏性操作只在领域事务成功后发 Success，失败保留旧摘要。
- [ ] 提交 RED。
- [ ] 改为 Room COUNT/DISTINCT/分页和 IO 调度器；UI 不持有完整集合。
- [ ] 运行聚焦和设备测试，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --tests "com.balancesentinel.app.data.repository.RoomDataStatisticsRepositoryTest" --rerun-tasks --no-parallel
```

### Task 26：Release 签名硬门禁

**文件：**

- Modify: `app/build.gradle.kts`, `.github/workflows/release.yml`, `.github/workflows/ci.yml`
- Create: `scripts/verify-release-signing-gate.ps1`

- [ ] 写行为脚本 RED：以明确不存在的 properties 路径运行 `assembleRelease`，当前若成功或错误不含 `RELEASE_SIGNING_CONFIG_REQUIRED` 则测试失败。
- [ ] 提交 RED 脚本。
- [ ] 支持 `-PwalletSentinel.signingConfigFile=<path>`，验证四字段非空和 storeFile 存在；Release 产物任务缺配置立即失败。
- [ ] `lintRelease`/非产物测试不因开发机无密钥失败；Release 只能使用 `signingConfigs.release`，删除 debug fallback。
- [ ] 脚本生成临时测试 keystore，验证 Release 成功且 apksigner 指纹不是 Debug；CI 对正式指纹白名单后才上传。
- [ ] 运行脚本、Debug build、lintRelease 并提交 GREEN。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-release-signing-gate.ps1
.\gradlew.bat assembleDebug lintRelease --rerun-tasks --no-parallel
```

### 阶段 4 门禁

运行阶段全部 JVM/仪器测试、Debug 编译、AndroidTest 编译、Lint 和签名脚本。使用测试签名链在模拟器执行同签名覆盖升级成功、异签名 `adb install -r` 被拒绝且旧应用数据仍在。

---

## 阶段 5：本地化、Widget 空态和无障碍

进入本阶段前，在 ledger 写入 `Phase 5 base: <git rev-parse HEAD 的完整输出>`。

### Task 27：本地化资源契约与区域格式器

**文件：**

- Modify: `res/values/strings.xml`, `res/values-en/strings.xml`
- Create: `util/LocalizedFormatter.kt`
- Modify: `util/FormatUtils.kt`, `InsightsScreen.kt`, `AccountBalanceCard.kt`
- Create: `LocalizedFormatterTest.kt`, `LocalizationResourceParityTest.kt`

- [ ] 写 RED：两语言 key 和 format placeholder 类型/序号一致。
- [ ] 验证中英文金额、日期、相对时间、复数和未知 ISO code 使用 locale；网络 ISO 和诊断机器格式保持不变。
- [ ] 提交 RED。
- [ ] 实现 `NumberFormat`/`DateFormat`/plurals 格式器，先切换余额卡和 Insights 数值面；本任务不清理所有页面字面量。
- [ ] 运行 JVM 聚焦测试并提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.util.LocalizedFormatterTest" --tests "com.balancesentinel.app.ui.LocalizationResourceParityTest" --rerun-tasks --no-parallel
```

### Task 28：主要页面和账户组件可见文本资源化

**文件：**

- Modify两语言 strings
- Modify: Home/Settings/Alert/Data/Clear/Backup/Log/Onboarding screens
- Modify: Add/Edit account、Provider fields components
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/LocalizationPrimaryUiTest.kt`; modify relevant UI tests

- [ ] 写主要页面和账户组件英文 RED，证明不存在已知中文字面量且长英文可换行。
- [ ] 验证所有句子组合使用格式资源/plurals，contentDescription 也来自资源；诊断协议、JSON key 和文件名不改。
- [ ] 提交 RED。
- [ ] 只迁移主要页面和账户组件，不触碰 Console、更新或 Widget surface。
- [ ] 运行资源 parity、AndroidTest 编译和 LocalizationPrimaryUiTest，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.LocalizationResourceParityTest" --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.LocalizationPrimaryUiTest --no-parallel
```

### Task 29：Console、更新和 Widget 可见文本资源化

**文件：**

- Modify两语言 strings
- Modify: ConsoleSelect/AddPlatform/Console/ConsoleComponents、`UpdateDialog.kt`, `ApkDownloader.kt`, `WidgetConfigActivity.kt`, `StaticWidgetProvider.kt`
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/LocalizationSecondaryUiTest.kt`; modify corresponding UI tests

- [ ] 分 Console、更新、Widget 三个既有入口写英文 RED，证明无中文字面量、句子拼接或硬编码 contentDescription。
- [ ] 提交 RED。
- [ ] 按 surface 迁移资源，每完成一组立即运行该组测试；不改诊断日志和机器协议。
- [ ] 运行资源 parity 和 LocalizationSecondaryUiTest，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.LocalizationResourceParityTest" --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.LocalizationSecondaryUiTest --no-parallel
```

### Task 30：Widget 真实空态

**文件：**

- Create: `widget/WidgetViewState.kt`, `WidgetStateResolver.kt`, `WidgetRemoteViewsRenderer.kt`
- Modify: `StaticWidgetProvider.kt`, `WidgetConfigActivity.kt`, 四个 `widget_balance*.xml`, 两语言 strings
- Create/modify: `WidgetStateResolverTest.kt`, `WidgetProviderTest.kt`, `WidgetRenderingTest.kt`

**状态：** Unconfigured、NoData、Fresh、Stale、PermissionRestricted、RefreshFailed。

- [ ] 写表驱动 RED：配置指向已删账户为 Unconfigured；旧数据+失败为 Stale；权限受限不能显示可用；无旧数据失败才 RefreshFailed。
- [ ] 验证每态中文/英文文案、数据时间、操作和 deep link。
- [ ] 提交 RED。
- [ ] 实现纯 resolver/renderer；Provider 不重复 getAll，消费 Room 摘要、最新刷新结果和 capability。
- [ ] 四布局按钮至少 48dp，动态文本换行，空态隐藏无关 detail。
- [ ] 运行 JVM 和设备 Widget 测试，提交 GREEN。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.widget.WidgetStateResolverTest" --tests "com.balancesentinel.app.widget.WidgetProviderTest" --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.widget.WidgetRenderingTest --no-parallel
```

### Task 31：主要页面和对话框的大字体与无障碍

**文件：**

- Modify: Onboarding/Home/Insights/Settings/Alert/Data/Backup/Update screens 及 Account/Add/Edit components、两语言 strings
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/AccessibilityWorkflowTest.kt`
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/LargeFontWorkflowTest.kt`
- Optional dependency: Espresso accessibility 3.6.1，只在设备测试需要时加入

- [ ] 写 RED：fontScale=2.0 时 onboarding、首页、账户卡、设置、导入预览和更新对话框关键操作可见、可滚动、不重叠。
- [ ] 验证无文字 IconButton 有本地化描述，Switch/Checkbox/展开项具有 role/stateDescription。
- [ ] 验证状态不只靠颜色，所有可点击图标 bounds 至少 48x48dp，焦点顺序符合视觉顺序。
- [ ] 提交 RED。
- [ ] 移除承载动态文本的固定高度，增加安全滚动、触控尺寸和语义；装饰图标明确 null。
- [ ] 在测试 finally 中恢复原 font scale；运行设备测试并提交 GREEN。

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.LargeFontWorkflowTest,com.balancesentinel.app.ui.AccessibilityWorkflowTest --no-parallel
```

### Task 32：Console 和 Widget 的大字体与无障碍

**文件：**

- Modify: `ConsoleSelectScreen.kt`, `AddPlatformScreen.kt`, `ConsoleScreen.kt`, `ConsoleComponents.kt`
- Modify: `WidgetConfigActivity.kt`, 四个 widget layout、两语言 strings
- Create: `ConsoleAccessibilityTest.kt`, `WidgetAccessibilityTest.kt`

- [ ] 写 RED：fontScale=2.0 下 Console 选择/添加/详情和 Widget 配置关键操作可见、可滚动、不重叠。
- [ ] 验证 Console 图标、展开项、复制操作和 Widget 刷新按钮的本地化语义、状态文字、48dp bounds 和焦点顺序。
- [ ] 提交 RED。
- [ ] 只修改 Console/Widget surface；复用 Task 31 语义模式，不复制业务逻辑。
- [ ] 运行对应设备测试并提交 GREEN。

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.ConsoleAccessibilityTest,com.balancesentinel.app.widget.WidgetAccessibilityTest --no-parallel
```

---

## 阶段 6：全量门禁与最终审查

### Task 33：模拟器与实机工作流验证

- [ ] 核对 `adb devices -l`，记录模拟器和实机 API、构建号及授权状态。
- [ ] 在 API 35 模拟器执行完整 `connectedDebugAndroidTest --rerun-tasks`。
- [ ] 验证旧版数据升级到 Room、进程被杀后任务恢复、设备重启、时区变化、通知拒绝/恢复、持续监控前台状态。
- [ ] 验证 90k 数据冷启动、分页、导入、导出和取消。
- [ ] 验证下载取消后立即重试、页面重建和 APK 安装。
- [ ] 使用测试签名链验证同签名覆盖升级成功、异签名失败且旧安装仍可用。
- [ ] 验证中文、英文、fontScale=2.0、Widget 六状态和关键屏幕阅读器流程。
- [ ] 任何设备发现先写行为级回归 RED，再恢复对应原 implementer 修复和独立复审。

### Task 34：最终回归、全量审查和分支收尾

按以下顺序串行执行并保存完整输出：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-parallel
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin compileReleaseKotlin compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
.\gradlew.bat lintDebug lintRelease --rerun-tasks --no-parallel
powershell -ExecutionPolicy Bypass -File scripts/verify-release-signing-gate.ps1
.\gradlew.bat connectedDebugAndroidTest --rerun-tasks --no-parallel
git diff --check 4c6a9581ed8484f11f3ff7b94630601b17354953..HEAD
git status --short
```

- [ ] 生成 `4c6a958..HEAD` scoped review package，校验提交数、大小、NUL、二进制和 diff check。
- [ ] 派 fresh read-only architecture reviewer，逐项核对本计划所有阻断和 Important 功能问题。
- [ ] 派 fresh read-only adversarial reviewer，检查修复 diff 新增的 Critical/Important。
- [ ] 确认两项 SECURITY-ONLY 仍只记录，未悄悄扩入源码。
- [ ] 只有所有 load-bearing finding 均 addressed 且无新 Critical/Important，才执行 `superpowers:verification-before-completion`。
- [ ] 最终执行 `superpowers:finishing-a-development-branch`，向用户提供纯中文交付结果、测试计数、安装包路径、已知非阻断风险和分支状态。

## 完成判定

- 四个数据损坏路径均有行为回归并具备可恢复提交。
- 账户使用稳定 UUID，凭据损坏不会被空数据覆盖。
- 历史主存储为 Room，90k 数据不再整库 JSON 复制。
- 所有刷新入口保留逐账户真实结果，旧缓存不会伪装成功。
- 普通后台任务不依赖脱离前台的 Service 或进程内 Handler。
- 导航、权限、下载、页面重建和并发状态符合设计。
- Release 无正式签名时不能生成可分发产物。
- 中文、英文、Widget 空态、大字体和无障碍通过设备验证。
- 全量测试、编译、Lint、差异检查和两次独立最终审查通过。
