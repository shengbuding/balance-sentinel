# 钱包哨兵全量加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复安全、数据完整性、并发、供应商解析、调度、WebView、备份和发布门禁问题，使主页、单账户、前台服务与 Widget 共用同一条可验证的余额刷新管线。

**Architecture:** 以 `AccountInfo.revision` 和每账户请求代次作为提交边界；`AccountBalanceRefresher` 只获取并校验数据，`RefreshResultCommitter` 只在账户与请求仍有效时提交副作用，`RefreshCoordinator` 是所有入口的唯一编排器。脚本、备份和 Console 各自拥有纯策略对象，Android 存储与 UI 只做适配，便于 JVM/Robolectric 测试覆盖真实行为。

**Tech Stack:** Kotlin 2.1、Java 17、Android API 35、Jetpack Compose、kotlinx.coroutines 1.9、kotlinx.serialization 1.7、OkHttp 4.12、Rhino 1.7.14、JUnit 4、Robolectric、MockK、MockWebServer、Kover。

## Global Constraints

- `compileSdk = 35`、`minSdk = 35`、`targetSdk = 35`，JVM target 保持 17。
- 不迁移 Room，不把全部调度重写为 WorkManager，不改变余额/历史/告警页面的主要交互结构。
- 原生余额契约仅允许 DeepSeek、StepFun、SiliconFlow、OpenRouter、Novita、ModelArk；其他供应商没有自定义脚本时必须失败，禁止猜测端点。
- 自定义脚本仅允许 HTTPS；默认仅账户 `baseUrl` 同源，额外 origin 逐项授权；HTTP、用户信息、IP 字面量、localhost、环回、私网、链路本地、组播、未指定地址和 DNS 解析到上述地址永久拒绝。
- 导入脚本保持禁用，只有静态 origin 可确定且用户确认全部额外 origin 后才可启用。
- 无凭据备份默认合并且绝不删除本地账户；只有 `credentialsIncluded=true`、所有账户凭据完整且用户二次确认时允许全量替换。
- Console 会话 TTL 固定为 30 天；过期读取必须立即删除；登录和 Dashboard 仅允许平台声明的精确 origin。
- Debug 单个请求体和响应体各最多保存 64 KiB；全局 LRU 字节预算固定为 2 MiB；Release 不安装或手工写入调试抓包。
- 每个行为变更严格执行 RED -> GREEN -> REFACTOR；RED 必须因缺少目标行为而失败，不能因编译错误、测试夹具错误或无关异常失败。
- 不把原始响应体、Cookie、Token、API Key 或 Secret 写入稳定错误、普通日志、无凭据备份或剪贴板。
- 每项任务提交前运行该任务列出的聚焦测试；最终完整 JVM 测试强制重跑两次，不依赖 Gradle 任务缓存。

---

## File Map

**账户与生命周期**

- `data/model/AccountInfo.kt`: 持久化账户、revision、脚本启用状态和授权 origin。
- `data/repository/ApiKeyManager.kt`: 单锁下原子读改写，不再暴露多次整表覆盖编辑流程。
- `data/repository/AccountLifecycleManager.kt`: Key 轮换/删除时迁移或清理关联数据与缓存。
- `ui/components/ProviderCredentialFields.kt`: 根据 `ProviderConfigs.getConfigFields()` 渲染凭据/设置。

**供应商与刷新**

- `data/api/balance/BalanceContract.kt`: 单端点、认证、解析和单位换算契约。
- `data/api/balance/BuiltInBalanceContracts.kt`: 六个原生供应商的唯一契约注册表。
- `data/refresh/RefreshModels.kt`: 稳定结果与错误分类。
- `data/refresh/AccountBalanceRefresher.kt`: 无副作用获取层。
- `data/refresh/RefreshResultCommitter.kt`: revision/token 复核和有序副作用提交。
- `data/refresh/RefreshCoordinator.kt`: 进程内共享的每账户请求代次与批量编排。
- `data/refresh/RefreshRuntime.kt`: Application 级单例装配。

**安全边界**

- `data/api/balance/ScriptNetworkPolicy.kt`: URL、origin、DNS 和重定向策略。
- `data/api/balance/RhinoScriptRunner.kt`: 指令观察与墙钟双重截止。
- `data/repository/BackupImportPlanner.kt`: 不可变导入预览和应用前置条件。
- `data/console/ConsoleOriginPolicy.kt`: WebView 导航与注入 origin 决策。
- `data/console/ConsoleSessionCleaner.kt`: 登出清理会话、WebStorage 和运行时 Cookie。
- `data/debug/SensitiveDataRedactor.kt`: 请求、响应、错误和剪贴板共用脱敏器。
- `data/debug/DebugCapture.kt`: 有界请求/响应读取与截断标记。

---

### Task 1: 账户 revision、原子编辑与动态凭据

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/model/AccountInfo.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/UnifiedModels.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/providers/ProviderConfigs.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/ApiKeyManager.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/repository/AccountLifecycleManager.kt`
- Create: `app/src/main/java/com/balancesentinel/app/ui/components/ProviderCredentialFields.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/components/AddAccountDialog.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/components/EditAccountDialog.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/HomeScreen.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/viewmodel/HomeViewModel.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/cache/ProviderCache.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/widget/BalanceWidgetDataStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/UsageDataStore.kt`
- Test: `app/src/test/java/com/balancesentinel/app/data/model/AccountInfoTest.kt`
- Test: `app/src/test/java/com/balancesentinel/app/data/repository/ApiKeyManagerTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/repository/AccountLifecycleManagerTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/providers/ProviderConfigsTest.kt`

**Interfaces:**
- Consumes: existing `RawRecordStore.migrateAccountIds`, `DailySummaryStore.migrateAccountIds`, `UsageDataStore.migrateAccountIds`, `BalanceWidgetDataStore.removeAccountBalance`, `ProviderCache.clear`.
- Produces: `AccountDraft`, `AccountSaveResult`, `ApiKeyManager.saveAccount(existingId, draft)`, `AccountLifecycleManager.save/delete`, persisted `AccountInfo.revision`, `usageScriptEnabled`, `authorizedScriptOrigins`.

- [ ] **Step 1: Write failing backward-compatibility and atomic-edit tests**

The mutation caught by these tests is removal of default revision/script policy fields or any return to multi-write account editing.

```kotlin
@Test
fun `old account json defaults revision and script policy`() {
    val old = """{"id":"a","label":"A","apiKey":"sk-12345678901"}"""
    val account = Json { ignoreUnknownKeys = true }.decodeFromString<AccountInfo>(old)

    assertEquals(0L, account.revision)
    assertTrue(account.usageScriptEnabled)
    assertTrue(account.authorizedScriptOrigins.isEmpty())
}

@Test
fun `editing one account never loses a concurrent account`() {
    manager.addAccount("A", "sk-aaaaaaaaaaa")
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    pool.submit { start.await(); manager.renameAccount(manager.computeId("sk-aaaaaaaaaaa"), "A2") }
    pool.submit { start.await(); manager.addAccount("B", "sk-bbbbbbbbbbb") }
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))

    assertEquals(setOf("A2", "B"), manager.getAccounts().map { it.label }.toSet())
    assertEquals(1L, manager.getAccounts().single { it.label == "A2" }.revision)
}
```

- [ ] **Step 2: Run tests and observe RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.model.AccountInfoTest" --tests "com.balancesentinel.app.data.repository.ApiKeyManagerTest" --rerun-tasks
```

Expected: FAIL because `revision`, script policy fields and a shared atomic mutation lock do not exist.

- [ ] **Step 3: Add exact account and field-storage models**

```kotlin
@Serializable
data class AccountInfo(
    val id: String,
    val label: String,
    val apiKey: String,
    val providerType: ProviderType = ProviderType.DEEPSEEK,
    val extraCredentials: Map<String, String> = emptyMap(),
    val extraSettings: Map<String, String> = emptyMap(),
    val usageScript: String? = null,
    val usageScriptEnabled: Boolean = true,
    val authorizedScriptOrigins: Set<String> = emptySet(),
    val revision: Long = 0
)

data class AccountDraft(
    val label: String,
    val apiKey: String,
    val providerType: ProviderType,
    val extraCredentials: Map<String, String>,
    val extraSettings: Map<String, String>,
    val usageScript: String?,
    val usageScriptEnabled: Boolean,
    val authorizedScriptOrigins: Set<String>
)

enum class ConfigFieldStorage { PRIMARY_CREDENTIAL, EXTRA_CREDENTIAL, SETTING }
```

Add `storage` to `ConfigField`; mark `apiKey` as `PRIMARY_CREDENTIAL`, `secretKey` as `EXTRA_CREDENTIAL`, and `baseUrl` as `SETTING`. `AccountInfo.toConfig()` must include `accountLabel`, all extra credentials, script text, enable state and canonical authorized origins.

- [ ] **Step 4: Implement one-lock account persistence and lifecycle side effects**

`ApiKeyManager` must use one process-wide lock for every `KEY_ACCOUNTS` read-modify-write and synchronous `commit()` before returning:

```kotlin
private val accountLock = ACCOUNT_LOCK

private inline fun <T> mutateAccounts(block: (MutableList<AccountInfo>) -> T): T =
    synchronized(accountLock) {
        val accounts = readAccountsLocked().toMutableList()
        val result = block(accounts)
        check(prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(accounts)).commit())
        result
    }

companion object {
    private val ACCOUNT_LOCK = Any()
}
```

`saveAccount(existingId, draft)` performs one mutation: unchanged Key keeps ID and increments revision; changed Key removes the old row and inserts the new ID with `old.revision + 1`; adding a new account starts at revision 0. Return one of:

```kotlin
sealed interface AccountSaveResult {
    data class Created(val account: AccountInfo) : AccountSaveResult
    data class Updated(val before: AccountInfo, val account: AccountInfo) : AccountSaveResult
    data class Replaced(val before: AccountInfo, val account: AccountInfo) : AccountSaveResult
}
```

`AccountLifecycleManager.save()` consumes this result. On `Replaced`, migrate raw history, summaries, usage and alert enable settings, then delete old Widget and Provider caches. On `delete`, remove the account, raw history, summaries, usage, all alert state, Widget cache, Provider cache and API debug entries. Put equivalent read-modify-write locks around Widget/usage/cache save and clear operations.

- [ ] **Step 5: Render provider fields dynamically and carry every credential**

```kotlin
@Composable
fun ProviderCredentialFields(
    fields: List<ConfigField>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit
)
```

`AddAccountDialog` and `EditAccountDialog` keep one `Map<String,String>` keyed by `ConfigField.key`, validate every required field, split values according to `ConfigField.storage`, and emit a complete `AccountDraft`. Remove the hard-coded `apiKey`/`baseUrl` callback tuple and pass `AccountDraft` through `HomeScreen` to `HomeViewModel`.

- [ ] **Step 6: Run GREEN tests and focused UI compilation**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.model.AccountInfoTest" --tests "com.balancesentinel.app.data.repository.ApiKeyManagerTest" --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --tests "com.balancesentinel.app.data.api.providers.ProviderConfigsTest" --rerun-tasks
.\gradlew.bat compileDebugKotlin
```

Expected: all selected tests PASS; Compose callbacks compile with no dropped `extraCredentials`.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "fix: make account edits atomic and revisioned"
```

---

### Task 2: 严格供应商余额契约与金额验证

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/api/balance/BalanceContract.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/api/balance/BuiltInBalanceContracts.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/balance/BalanceProvider.kt`
- Rewrite: `app/src/main/java/com/balancesentinel/app/data/api/balance/BalanceQueryService.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/balance/PresetScripts.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/providers/OpenAiCompatibleProvider.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/providers/DeepSeekProvider.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/ProviderResult.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/ProviderFactory.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/balance/BuiltInBalanceContractsTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/balance/BalanceQueryServiceTest.kt`
- Create: `app/src/test/resources/balance/deepseek.json`
- Create: `app/src/test/resources/balance/stepfun.json`
- Create: `app/src/test/resources/balance/siliconflow.json`
- Create: `app/src/test/resources/balance/openrouter.json`
- Create: `app/src/test/resources/balance/novita.json`
- Create: `app/src/test/resources/balance/model_ark.json`
- Modify: `app/src/test/java/com/balancesentinel/app/data/api/ProviderIntegrationTest.kt`

**Interfaces:**
- Consumes: `ProviderType`, `ProviderConfig`, `UnifiedBalance`, OkHttp `Call.Factory`.
- Produces: `BalanceContract`, `BuiltInBalanceContracts.resolve(providerType, baseUrl)`, `BalanceQueryService.queryBalance(config)`, strict `ProviderResult` failures.

- [ ] **Step 1: Write parser and one-request failing tests**

```kotlin
@Test
fun `missing invalid and non finite amounts fail while explicit zero succeeds`() {
    val contract = BuiltInBalanceContracts.deepSeek
    val invalid = listOf(
        """{"is_available":true,"balance_infos":[{"currency":"CNY"}]}""",
        """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"oops"}]}""",
        """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"NaN"}]}""",
        """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"Infinity"}]}"""
    )

    invalid.forEach { assertTrue(contract.parse(it, ProviderType.DEEPSEEK, "acct") is ProviderResult.Failure) }
    val zero = contract.parse(
        """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"0"}]}""",
        ProviderType.DEEPSEEK,
        "acct"
    ) as ProviderResult.Success
    assertEquals(0.0, zero.data.balances.single().totalBalance, 0.0)
}

@Test
fun `novita fixture converts ten thousandth dollars exactly once`() {
    val result = BuiltInBalanceContracts.novita.parse(
        resource("balance/novita.json"), ProviderType.CUSTOM, "acct"
    ) as ProviderResult.Success
    assertEquals(12.3456, result.data.balances.single().totalBalance, 0.0000001)
}

@Test
fun `unsupported provider does not probe generic endpoints`() = runTest {
    val server = MockWebServer().also { it.start() }
    val service = BalanceQueryService(OkHttpClient(), endpointOverride = { server.url("/") })

    val result = service.queryBalance(config(ProviderType.MOONSHOT, server.url("/").toString()))

    assertTrue(result is ProviderResult.Failure)
    assertEquals(0, server.requestCount)
    server.shutdown()
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.BuiltInBalanceContractsTest" --tests "com.balancesentinel.app.data.api.balance.BalanceQueryServiceTest" --rerun-tasks
```

Expected: FAIL because parser absence currently becomes zero, Novita has two disagreeing conversions, and unknown providers probe five endpoints.

- [ ] **Step 3: Implement one contract per native provider**

```kotlin
interface BalanceContract {
    val type: BalanceProviderType
    val endpoint: HttpUrl
    fun request(apiKey: String, endpoint: HttpUrl = this.endpoint): Request
    fun parse(body: String, providerType: ProviderType, accountId: String): ProviderResult<UnifiedBalance>
}

internal fun JsonObject.requiredFiniteDouble(name: String): Double {
    val primitive = this[name]?.jsonPrimitive
        ?: throw SerializationException("missing amount field: $name")
    val value = primitive.content.toDoubleOrNull()
        ?: throw SerializationException("invalid amount field: $name")
    if (!value.isFinite()) throw SerializationException("non-finite amount field: $name")
    return value
}
```

Use exactly these endpoint/parser pairs:

| Provider | Endpoint | Required amount | Unit rule |
|---|---|---|---|
| DeepSeek | `https://api.deepseek.com/user/balance` | each `balance_infos[].total_balance` | response `currency` |
| StepFun | `https://api.stepfun.com/v1/accounts` | root `balance` | `CNY` |
| SiliconFlow | `https://api.siliconflow.cn/v1/user/info` or `.com` | `data.balance` | `.cn = CNY`, `.com = USD` |
| OpenRouter | `https://openrouter.ai/api/v1/credits` | `data.total_credits`, `data.total_usage` | `USD`, remaining = credits - usage |
| Novita | `https://api.novita.ai/v3/user/balance` | root `availableBalance` | divide by 10000 once, `USD` |
| ModelArk | `https://ai.gitee.com/v1/tokens/packages/balance` | root `balance` | `Token` |

`BalanceProviderType.detectFromUrl` must parse with `toHttpUrlOrNull()` and compare canonical host exactly; substring matching is forbidden. `BuiltInBalanceContracts.resolve` resolves DeepSeek/ModelArk by `ProviderType` and the other contracts only from a recognized custom base origin.

- [ ] **Step 4: Remove duplicate parsers and generic probing**

`BalanceQueryService` sends one request to the resolved contract and maps 401, 429, network, 5xx and schema errors without response bodies. Delete `queryCustomProvider`, `extractBalanceFromResponse`, the five-endpoint arrays, and all built-in parser scripts from `PresetScripts`; keep only `getCustomTemplate()` for user-authored scripts. `OpenAiCompatibleProvider` uses a user script when configured, otherwise calls the strict service once and returns `ApiUnavailableError("该供应商需要自定义余额脚本")` when no contract resolves.

- [ ] **Step 5: Run GREEN and mutation cases**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.*" --tests "com.balancesentinel.app.data.api.ProviderIntegrationTest" --rerun-tasks
```

Expected: PASS; changing Novita division, accepting NaN, or adding a fallback request makes at least one test fail.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "fix: enforce strict balance provider contracts"
```

---

### Task 3: 共享刷新领域管线与过期提交防护

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/refresh/RefreshModels.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/refresh/AccountBalanceRefresher.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/refresh/RefreshResultCommitter.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/refresh/RefreshCoordinator.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/refresh/RefreshRuntime.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/RawRecordStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/RefreshLogStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/UsageDataStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/widget/BalanceWidgetDataStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/cache/ProviderCache.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/refresh/AccountBalanceRefresherTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/refresh/RefreshCoordinatorTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/refresh/RefreshResultCommitterTest.kt`

**Interfaces:**
- Consumes: Task 1 `AccountInfo.revision`, Task 2 strict provider result, real cache/history/usage/alert stores.
- Produces: `RefreshGateway`, stable `RefreshFailure`, `RefreshRequest`, `AccountRefreshResult`, shared `RefreshCoordinator`.

- [ ] **Step 1: Write failing latest-wins and no-side-effect tests**

```kotlin
@Test
fun `older completion is stale and only newest result commits`() = runTest {
    val first = CompletableDeferred<BalanceFetchResult>()
    val second = CompletableDeferred<BalanceFetchResult>()
    val source = QueueBalanceSource(first, second)
    val committer = RecordingCommitter()
    val coordinator = RefreshCoordinator(accounts, source, committer, backgroundScope)

    val old = async { coordinator.refreshAccount("acct", RefreshTrigger.MANUAL_ACCOUNT) }
    source.awaitFirstStarted()
    val newest = async { coordinator.refreshAccount("acct", RefreshTrigger.WIDGET) }
    source.awaitSecondStarted()
    second.complete(success(20.0))
    first.complete(success(10.0))

    assertTrue(newest.await() is AccountRefreshResult.Committed)
    assertTrue(old.await() is AccountRefreshResult.Stale)
    assertEquals(listOf(20.0), committer.committedBalances)
}

@Test
fun `edited account revision rejects an in flight result without side effects`() = runTest {
    val request = RefreshRequest("acct", revision = 2, token = 7, RefreshTrigger.SERVICE, 100)
    accounts.replace(account(revision = 3))

    val result = committer.commit(request, success(99.0)) { true }

    assertTrue(result is AccountRefreshResult.Stale)
    assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
    assertTrue(BalanceWidgetDataStore.getAllBalances(context).isEmpty())
    assertNull(cache.get(ProviderType.DEEPSEEK, "acct"))
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.*" --rerun-tasks
```

Expected: FAIL because the coordinator, revision/token gate and typed outcomes do not exist.

- [ ] **Step 3: Define stable request, result and error types**

```kotlin
enum class RefreshTrigger { MANUAL_ALL, MANUAL_ACCOUNT, SERVICE, WIDGET, WATCHDOG }

sealed interface RefreshFailure {
    val message: String
    data class NetworkFailure(override val message: String) : RefreshFailure
    data class AuthenticationFailure(override val message: String) : RefreshFailure
    data class RateLimited(override val message: String) : RefreshFailure
    data class ResponseSchemaFailure(override val message: String) : RefreshFailure
    data class ScriptTimeout(override val message: String) : RefreshFailure
    data class ScriptPolicyDenied(override val message: String) : RefreshFailure
    data class AccountStale(override val message: String) : RefreshFailure
    data class PersistenceFailure(override val message: String) : RefreshFailure
}

data class RefreshRequest(
    val accountId: String,
    val revision: Long,
    val token: Long,
    val trigger: RefreshTrigger,
    val startedAt: Long
)

sealed interface AccountRefreshResult {
    val accountId: String
    data class Committed(override val accountId: String, val balance: UnifiedBalance) : AccountRefreshResult
    data class Failed(override val accountId: String, val failure: RefreshFailure) : AccountRefreshResult
    data class Stale(override val accountId: String, val failure: RefreshFailure.AccountStale) : AccountRefreshResult
    data class Skipped(override val accountId: String, val reason: String) : AccountRefreshResult
}
```

Error messages must be bounded diagnostic summaries and never include raw bodies, headers, Cookie values or credentials.

- [ ] **Step 4: Implement side-effect-free fetch and guarded commit**

```kotlin
fun interface AccountBalanceSource {
    suspend fun fetch(account: AccountInfo): BalanceFetchResult
}

interface RefreshCommitter {
    fun commit(
        request: RefreshRequest,
        fetched: BalanceFetchResult.Success,
        isLatest: () -> Boolean
    ): AccountRefreshResult
}

interface RefreshGateway {
    suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult
    suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult>
    fun invalidate(accountId: String)
}
```

`AccountBalanceRefresher` maps one account to one provider/script path and returns data only. `RefreshResultCommitter` uses one commit lock; before any write it checks `isLatest()`, reloads the account, and compares ID and revision. A valid success commits in this exact order: Provider cache, replacement of that account's Widget cache, one `RawRecordStore.addRecords` call stamped at response completion, refresh logs, usage snapshot, `(accountId,currency)` alerts, Widget redraw notification. Fetch failure and stale results write nothing and preserve prior cache.

- [ ] **Step 5: Implement per-account monotonic coordination**

```kotlin
private val generations = ConcurrentHashMap<String, AtomicLong>()

private fun nextToken(accountId: String): Long =
    generations.computeIfAbsent(accountId) { AtomicLong(0) }.incrementAndGet()

override suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult> =
    supervisorScope {
        accountStore.getAccounts().map { account ->
            async { refreshAccount(account.id, trigger) }
        }.awaitAll()
    }
```

The token is allocated before fetch; commit receives `isLatest = { generations[id]?.get() == token }`. `invalidate(id)` increments the generation so delete/edit callers can immediately obsolete prior work.

- [ ] **Step 6: Run GREEN tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.*" --tests "com.balancesentinel.app.data.repository.RawRecordStoreTest" --tests "com.balancesentinel.app.widget.BalanceWidgetDataStoreTest" --rerun-tasks
```

Expected: PASS; reversing completion order, changing revision or throwing persistence errors yields typed results and no stale writes.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "feat: add guarded shared refresh pipeline"
```

---

### Task 4: 将主页、服务与 Widget 迁入共享管线

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/DeepSeekApp.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/viewmodel/HomeViewModel.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/service/BalanceRefreshService.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/widget/StaticWidgetProvider.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/AccountLifecycleManager.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/ui/viewmodel/HomeViewModelTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/widget/WidgetProviderTest.kt`
- Modify: `app/src/androidTest/java/com/balancesentinel/app/service/BalanceRefreshServiceTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/service/BalanceRefreshRunnerTest.kt`

**Interfaces:**
- Consumes: Task 3 `RefreshGateway` and typed results.
- Produces: one `RefreshRuntime` instance per Application process; all four refresh entry points call it.

- [ ] **Step 1: Write failing entry-point behavior tests**

```kotlin
@Test
fun `widget refresh uses account provider through shared gateway`() = runTest {
    val account = account(providerType = ProviderType.MODEL_ARK, apiKey = "model-key-12345")
    accounts.replaceAll(listOf(account))
    val gateway = RecordingRefreshGateway(committed(account.id, 8.0, "Token"))

    WidgetRefreshRunner(context, accounts, gateway).refreshNow()

    assertEquals(listOf(account.id to RefreshTrigger.WIDGET), gateway.calls)
    assertTrue(BalanceWidgetDataStore.getAllBalances(context).none { it.currency == "CNY" })
}

@Test
fun `single account refresh has the same history side effects as refresh all`() = runTest {
    viewModel.refreshSingleAccount("acct")
    advanceUntilIdle()
    val singleCount = RawRecordStore.getAllRecords(context).size
    clearRefreshStores()
    viewModel.refreshBalance()
    advanceUntilIdle()

    assertEquals(singleCount, RawRecordStore.getAllRecords(context).size)
    assertTrue(singleCount > 0)
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.widget.WidgetProviderTest" --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --tests "com.balancesentinel.app.service.BalanceRefreshRunnerTest" --rerun-tasks
```

Expected: FAIL because Widget calls `DeepSeekApiService` for every Key and single-account refresh has a separate incomplete side-effect path.

- [ ] **Step 3: Install the Application-scoped runtime**

```kotlin
class DeepSeekApp : Application() {
    lateinit var refreshGateway: RefreshGateway
        private set

    override fun onCreate() {
        super.onCreate()
        refreshGateway = RefreshRuntime.create(this)
    }
}

object RefreshRuntime {
    fun from(context: Context): RefreshGateway =
        (context.applicationContext as DeepSeekApp).refreshGateway
}
```

Tests inject `RefreshGateway`; production obtains it only from `DeepSeekApp`. `AccountLifecycleManager` calls `gateway.invalidate(oldId)` before edit/delete persistence.

- [ ] **Step 4: Replace all duplicated refresh bodies**

`HomeViewModel.refreshSingleAccount` and `refreshBalance` call the gateway in `viewModelScope`, map `Committed` balances to `BalanceResponse`, preserve cached values on `Failed`, and show the stable failure message. `BalanceRefreshService` owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, calls `refreshAll(SERVICE)`, derives notification totals from committed Widget storage, and cancels the scope in `onDestroy`. `StaticWidgetProvider` uses `goAsync()` plus `refreshAll(WIDGET/WATCHDOG)` and never instantiates `DeepSeekApiService`, `ProviderFactory` or reads a Key for network use.

Delete the old per-entry cache/history/log/usage/alert loops and the manual `ApiDebugStore.addEntry` in `HomeViewModel`; those side effects belong only to the committer.

- [ ] **Step 5: Run GREEN and Android service tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.widget.WidgetProviderTest" --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --tests "com.balancesentinel.app.service.BalanceRefreshRunnerTest" --rerun-tasks
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.service.BalanceRefreshServiceTest
```

Expected: JVM tests PASS. The connected test must PASS when an API 35 device/emulator is available; if none is connected, record that exact environment gap in the final verification report rather than claiming device coverage.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "refactor: route every refresh through coordinator"
```

---

### Task 5: 脚本网络策略、导入检查与 Rhino 双重超时

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/api/balance/ScriptNetworkPolicy.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/api/balance/RhinoScriptRunner.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/api/balance/ScriptInspection.kt`
- Rewrite: `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScript.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/providers/OpenAiCompatibleProvider.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/refresh/AccountBalanceRefresher.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/balance/ScriptNetworkPolicyTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/balance/RhinoScriptRunnerTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutorTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/api/balance/UsageScriptSecurityTest.kt`

**Interfaces:**
- Consumes: Task 1 account script policy fields and Task 3 `RefreshFailure`.
- Produces: `WebOrigin`, `ScriptNetworkPolicy.validate`, `ScriptInspection`, bounded `RhinoScriptRunner.run`, typed `ScriptExecutionResult`.

- [ ] **Step 1: Write failing policy and timeout tests**

```kotlin
@Test
fun `policy rejects unsafe destinations and dns rebinding`() {
    val policy = ScriptNetworkPolicy(
        baseUrl = "https://api.example.com:8443/v1".toHttpUrl(),
        authorizedOrigins = setOf(WebOrigin.https("cdn.example.com")),
        resolver = FakeResolver(
            "api.example.com" to listOf("93.184.216.34"),
            "cdn.example.com" to listOf("10.0.0.7")
        )
    )

    assertTrue(policy.validate("https://api.example.com:8443/balance".toHttpUrl()).isAllowed)
    assertFalse(policy.validate("http://api.example.com:8443/balance".toHttpUrl()).isAllowed)
    assertFalse(policy.validate("https://127.0.0.1/balance".toHttpUrl()).isAllowed)
    assertFalse(policy.validate("https://cdn.example.com/balance".toHttpUrl()).isAllowed)
}

@Test(timeout = 3_000)
fun `configuration infinite loop hits wall clock deadline`() = runBlocking {
    val script = UsageScript("""while (true) {}""", timeout = 1)

    assertTrue(executor.inspect(script, account).failure is RefreshFailure.ScriptTimeout)
}

@Test(timeout = 3_000)
fun `extractor infinite loop hits its own wall clock deadline`() = runBlocking {
    val script = UsageScript(
        """({request:{url:"https://api.example.com/x"},extractor:function(r){while(true){}}})""",
        timeout = 1
    )

    assertTrue(executor.extractForTest(script, account, "{}") is ScriptExecutionResult.Failure)
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.RhinoScriptRunnerTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --rerun-tasks
```

Expected: FAIL because arbitrary HTTP(S) URLs are accepted, the instruction threshold is never set, and Rhino has no wall-clock cancellation.

- [ ] **Step 3: Implement canonical origin and global-unicast validation**

```kotlin
data class WebOrigin(val scheme: String, val host: String, val port: Int) {
    companion object {
        fun from(url: HttpUrl) = WebOrigin(url.scheme, IDN.toASCII(url.host).lowercase(), url.port)
        fun https(host: String) = WebOrigin("https", IDN.toASCII(host).lowercase(), 443)
    }
}

fun interface HostResolver {
    fun lookup(host: String): List<InetAddress>
}

data class ScriptPolicyDecision(val isAllowed: Boolean, val reason: String? = null)
```

`validate()` rejects non-HTTPS, user info, IP literals and non-canonical hosts before DNS. Same-origin must equal the full base origin, including its registered port. Extra origins must be explicitly authorized and port 443. After resolution, every address must be global unicast; explicitly reject IPv4 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.168/16, multicast/reserved ranges and IPv6 unspecified, loopback, `fc00::/7`, `fe80::/10`, multicast.

The HTTP transport disables automatic redirects, follows at most five redirects manually, resolves a relative `Location` against the current URL, and calls the same policy before every request and redirect. Production uses the validated DNS result for the corresponding OkHttp connection; do not validate with one resolver and connect with another.

- [ ] **Step 4: Add instruction and wall-clock deadlines**

```kotlin
private const val INSTRUCTION_THRESHOLD = 10_000

override fun makeContext(): Context = super.makeContext().apply {
    optimizationLevel = -1
    instructionObserverThreshold = INSTRUCTION_THRESHOLD
}

override fun observeInstructionCount(cx: Context, count: Int) {
    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos.get()) {
        throw ScriptDeadlineExceeded()
    }
}
```

`RhinoScriptRunner.run(timeoutMillis, phase, block)` creates a dedicated single-thread executor, sets a monotonic deadline in `ThreadLocal`, waits with `Future.get(timeoutMillis, MILLISECONDS)`, cancels on timeout, and always calls `shutdownNow()` plus bounded termination. Request-config evaluation and extractor evaluation each receive a new full timeout; HTTP timeout remains separate.

- [ ] **Step 5: Refactor execution and safe inspection**

```kotlin
sealed interface ScriptExecutionResult {
    data class Success(val balances: List<BalanceData>) : ScriptExecutionResult
    data class Failure(val failure: RefreshFailure) : ScriptExecutionResult
}

data class ScriptInspection(
    val request: RequestConfig?,
    val requiredExtraOrigins: Set<WebOrigin>,
    val staticallyDeterminable: Boolean,
    val failure: RefreshFailure? = null
)
```

`inspect()` substitutes placeholder credentials, evaluates only the configuration phase in Rhino, never creates an HTTP call, and reports canonical extra origins. Dynamic/unresolvable request URLs set `staticallyDeterminable=false`. `execute()` refuses `usageScriptEnabled=false`, applies `ScriptNetworkPolicy`, validates required finite `remaining`, and returns `ScriptTimeout`, `ScriptPolicyDenied` or `ResponseSchemaFailure` without side effects. Replace the literal NUL source byte with the textual Kotlin escape `"\u0000"`.

Add `okhttp-tls` as a test-only version-catalog dependency for HTTPS MockWebServer tests. Security tests cover same-origin success, authorized public origin success, HTTP, unauthorized origin, private DNS result and cross-origin redirect denial; a test-only connector may route the approved public test host to loopback, while the production resolver/connector remains identical.

- [ ] **Step 6: Run GREEN security suite**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.RhinoScriptRunnerTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --rerun-tasks
```

Expected: PASS; both infinite-loop phases terminate within the asserted bound and all denied requests leave MockWebServer request counts unchanged after the denial point.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test app/build.gradle.kts gradle/libs.versions.toml
git commit -m "fix: sandbox script network and execution"
```

---

### Task 6: 备份 schema v2、非破坏导入计划与确认 UI

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/ConfigManager.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/repository/BackupImportPlanner.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/ApiKeyManager.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/BackupRestoreScreen.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/viewmodel/HomeViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/ConfigManagerTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/repository/BackupImportPlannerTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/ui/viewmodel/HomeViewModelTest.kt`
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/screen/BackupRestoreScreenTest.kt`

**Interfaces:**
- Consumes: Task 1 account policy fields, Task 5 `ScriptInspection`.
- Produces: `AppConfig(version=2, credentialsIncluded)`, immutable `BackupImportPlan`, `ImportMode`, preview/apply flow.

- [ ] **Step 1: Write failing redaction, merge and replace-gate tests**

```kotlin
@Test
fun `credential free export recursively removes secrets scripts and grants`() {
    val json = ConfigManager.buildConfig(context, keys, prefs, includeTokens = false)
    val exported = ConfigManager.decode(json)
    val account = exported.accounts.single()

    assertEquals(2, exported.version)
    assertFalse(exported.credentialsIncluded)
    assertEquals("", account.apiKey)
    assertTrue(account.extraCredentials.values.all(String::isEmpty))
    assertNull(account.usageScript)
    assertFalse(account.usageScriptEnabled)
    assertTrue(account.authorizedScriptOrigins.isEmpty())
}

@Test
fun `sanitized merge preserves every local account and local credential`() {
    val local = account(id = "same", apiKey = "sk-local-secret", label = "old")
    val incoming = local.copy(label = "new", apiKey = "", extraCredentials = mapOf("secretKey" to ""))
    val plan = planner.plan(config(false, listOf(incoming)), listOf(local), ImportMode.MERGE)

    assertEquals("sk-local-secret", plan.finalAccounts.single().apiKey)
    assertEquals("new", plan.finalAccounts.single().label)
    assertEquals(0, plan.deletedCount)
}

@Test
fun `replace is impossible without complete credential backup and confirmation`() {
    val sanitized = planner.plan(config(false, emptyList()), listOf(account()), ImportMode.REPLACE_ALL)
    assertFalse(sanitized.canApply)
    assertFailsWith<IllegalStateException> { planner.apply(sanitized, confirmedFullReplace = true) }

    val complete = planner.plan(config(true, listOf(account())), listOf(account(id = "old")), ImportMode.REPLACE_ALL)
    assertFailsWith<IllegalStateException> { planner.apply(complete, confirmedFullReplace = false) }
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.ConfigManagerTest" --tests "com.balancesentinel.app.data.repository.BackupImportPlannerTest" --rerun-tasks
```

Expected: FAIL because v1 has no credential marker, sanitized import replaces all accounts, and import applies before preview.

- [ ] **Step 3: Define schema and immutable plan types**

```kotlin
@Serializable
data class AppConfig(
    val version: Int = 2,
    val credentialsIncluded: Boolean = false,
    val exportedAt: String,
    val appVersion: String,
    val accounts: List<AccountInfo>,
    val settings: ConfigSettings
)

enum class ImportMode { MERGE, REPLACE_ALL }

data class BackupImportPlan(
    val mode: ImportMode,
    val finalAccounts: List<AccountInfo>,
    val matchedUpdatedCount: Int,
    val retainedCredentialCount: Int,
    val createdCount: Int,
    val skippedCount: Int,
    val conflictCount: Int,
    val deletedCount: Int,
    val scriptAuthorizations: List<ScriptAuthorization>,
    val canApply: Boolean,
    val blockingReasons: List<String>,
    val settings: ConfigSettings
)

data class ScriptAuthorization(
    val accountId: String,
    val requiredExtraOrigins: Set<WebOrigin>,
    val staticallyDeterminable: Boolean
)
```

- [ ] **Step 4: Implement deterministic v1/v2 planning**

Without-token export keeps IDs and non-sensitive settings, writes an empty `apiKey`, empties every `extraCredentials` value, removes script text and authorized origins, and disables script execution. Full export sets `credentialsIncluded=true` and retains credentials.

Planner rules are exact:

1. Reject duplicate incoming IDs as conflicts.
2. In MERGE, match by ID. Sanitized matches keep all local credentials, script and grants; update label, provider type and fields whose `ConfigField.storage == SETTING`. If the new provider's required credential fields are not satisfied locally, leave that account unchanged and count a conflict.
3. Sanitized unmatched accounts are skipped. No local account is deleted.
4. Full-credential matches/unmatched accounts are accepted only when `computeId(apiKey)` matches the normalized ID and all required credential fields are nonblank. Imported scripts retain code but set `usageScriptEnabled=false` and clear grants.
5. REPLACE_ALL sets `canApply=true` only when `credentialsIncluded=true`, every final account is complete, there are no conflicts, and every script is either statically inspectable or remains disabled.
6. Schema v1 infers completeness from actual credential fields, normalizes legacy 8-character IDs, and always produces a preview before application.

`apply(plan, confirmedFullReplace)` refuses a false `canApply`; REPLACE_ALL additionally refuses a false confirmation. Account persistence is one `ApiKeyManager.replaceAll` commit. Apply settings only after account persistence succeeds.

- [ ] **Step 5: Add preview, origin authorization and double confirmation UI**

`BackupRestoreScreen` parses a file and stores a pending plan without changing data. Its dialog shows matched/retained/created/skipped/conflict/deleted counts, defaults to MERGE, and disables apply for blocking reasons. Imported scripts list each canonical extra origin with a checkbox; enable a script only when inspection is static and all required origins are checked. Selecting REPLACE_ALL first shows the plan, then a second destructive confirmation that includes the deletion count.

Use resource strings for all new Chinese/English copy. On success reload UI/cache but do not automatically run a disabled script.

- [ ] **Step 6: Run GREEN tests and UI test**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.ConfigManagerTest" --tests "com.balancesentinel.app.data.repository.BackupImportPlannerTest" --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --rerun-tasks
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.screen.BackupRestoreScreenTest
```

Expected: JVM tests PASS. Connected UI test verifies that merely selecting a file does not alter accounts and that replace requires both dialogs.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "fix: make backup imports previewed and non destructive"
```

---

### Task 7: Console 精确 origin、统一 TTL 与完整登出清理

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/ui/console/ConsoleModels.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/console/AddPlatformScreen.kt`
- Rewrite: `app/src/main/java/com/balancesentinel/app/data/console/store/ConsoleStore.kt`
- Delete: `app/src/main/java/com/balancesentinel/app/data/console/auth/AuthModels.kt`
- Delete: `app/src/main/java/com/balancesentinel/app/data/console/auth/ConsoleAuthProvider.kt`
- Delete: `app/src/main/java/com/balancesentinel/app/data/console/store/ConsoleSessionStore.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/console/ConsoleOriginPolicy.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/console/ConsoleSessionCleaner.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/viewmodel/ConsoleViewModel.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/console/ConsoleScreen.kt`
- Delete: `app/src/test/java/com/balancesentinel/app/data/console/ConsoleSessionStoreTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/console/ConsoleSessionTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/console/ConsoleStoreTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/console/ConsoleOriginPolicyTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/console/ConsoleSessionCleanerTest.kt`
- Create: `app/src/androidTest/java/com/balancesentinel/app/ui/console/ConsoleWebViewSecurityTest.kt`

**Interfaces:**
- Consumes: `ConsolePlatform.loginUrl/dashboardUrl`, Android `CookieManager` and `WebStorage` adapters.
- Produces: `ConsoleOriginPolicy.decideNavigation/canInjectLocalStorage`, `ConsoleStore.getValidSession`, `ConsoleSessionCleaner.logout`.

- [ ] **Step 1: Write failing TTL, origin and logout tests**

```kotlin
@Test
fun `expired session is deleted on read`() {
    store.saveSession("deepseek", session(lastActiveTime = NOW - THIRTY_DAYS_MS - 1))

    assertNull(store.getValidSession("deepseek", now = NOW))
    assertNull(store.getSession("deepseek"))
}

@Test
fun `storage injection requires exact dashboard origin`() {
    val policy = ConsoleOriginPolicy(platform)

    assertTrue(policy.canInjectLocalStorage("https://platform.deepseek.com/overview"))
    assertFalse(policy.canInjectLocalStorage("https://platform.deepseek.com.evil.example/overview"))
    assertFalse(policy.canInjectLocalStorage("https://evil.example/?next=platform.deepseek.com"))
}

@Test
fun `logout clears encrypted session origins and all runtime cookies`() {
    cleaner.logout(platform)

    assertNull(store.getSession(platform.id))
    assertEquals(setOf("https://platform.deepseek.com"), webStorage.deletedOrigins)
    assertEquals(1, cookies.removeAllCalls)
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.console.*" --rerun-tasks
```

Expected: FAIL because UI treats any non-null session as logged in, `isSessionExpired` is hard-coded false, and WebView injects storage after arbitrary navigation.

- [ ] **Step 3: Consolidate the session model and enforce TTL on every read**

```kotlin
@Serializable
data class ConsoleSession(
    val cookies: Map<String, String> = emptyMap(),
    val localStorage: Map<String, String> = emptyMap(),
    val token: String? = null,
    val email: String? = null,
    val loginTime: Long,
    val lastActiveTime: Long
) {
    fun isValid(now: Long): Boolean = now - lastActiveTime < THIRTY_DAYS_MS
}

fun getValidSession(platformId: String, now: Long = clock()): ConsoleSession? {
    val session = getSession(platformId) ?: return null
    if (session.isValid(now)) return session
    removeSession(platformId)
    return null
}
```

`ConsoleViewModel` initializes and refreshes only through `getValidSession`; remove `session != null` validity checks and the private never-expiring helper. Delete the deprecated second session model/store so there is one TTL implementation.

- [ ] **Step 4: Implement structured origin/navigation policy**

```kotlin
sealed interface NavigationDecision {
    data object AllowInWebView : NavigationDecision
    data class OpenExternal(val uri: Uri) : NavigationDecision
    data object Reject : NavigationDecision
}

class ConsoleOriginPolicy(platform: ConsolePlatform) {
    val loginOrigin: WebOrigin
    val dashboardOrigin: WebOrigin
    val allowedOrigins: Set<WebOrigin>
    fun decideNavigation(url: String): NavigationDecision
    fun canInjectLocalStorage(url: String): Boolean
}
```

Both configured URLs must be valid HTTPS. Main-frame navigation to either exact origin is allowed; other HTTP(S) URLs launch `ACTION_VIEW`; every other scheme is rejected. Host substring checks are forbidden. Cookie injection calls `setCookie(platform.loginUrl, cookie)` once per cookie and only for the login origin. `localStorage` injection runs only when `onPageFinished` origin equals the Dashboard origin. API interception compares parsed host membership, not `String.contains`.

- [ ] **Step 5: Implement complete logout cleanup**

`ConsoleSessionCleaner.logout(platform, completion)` removes the encrypted session, calls `WebStorage.deleteOrigin` for the distinct login/dashboard origins, then calls `CookieManager.removeAllCookies` and `flush`. Runtime cookies are intentionally global because unknown and HttpOnly cookies cannot be enumerated reliably; other platforms retain encrypted sessions and reinject on their next open.

- [ ] **Step 6: Run GREEN and WebView device tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.console.*" --rerun-tasks
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.ui.console.ConsoleWebViewSecurityTest
```

Expected: JVM tests PASS. Device test verifies allowed-origin injection, external browser dispatch, and no session restoration after logout.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "fix: lock console sessions to trusted origins"
```

---

### Task 8: 调度健康状态、Keepalive 判定与合规服务启动

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/RefreshScheduler.kt`
- Create: `app/src/main/java/com/balancesentinel/app/service/ForegroundServiceStarter.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/service/BalanceRefreshService.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/receiver/KeepAliveReceiver.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/receiver/BootReceiver.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/widget/StaticWidgetProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/RefreshSchedulerTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/receiver/KeepAliveReceiverTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/receiver/BootReceiverTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/service/ForegroundServiceStarterTest.kt`

**Interfaces:**
- Consumes: Task 4 shared refresh gateway.
- Produces: persisted `refreshDeadlineAt`, pure `ServiceHealthEvaluator.shouldRestart`, typed `ServiceStartResult`, distinct `ACTION_REFRESH_NOW`/`ACTION_WATCHDOG`.

- [ ] **Step 1: Write failing long-interval and watchdog tests**

```kotlin
@Test
fun `stale heartbeat does not restart before next refresh is overdue`() {
    val state = ServiceHealthState(
        expectedNextAt = NOW + 20 * 60_000,
        lastHeartbeat = NOW - 5 * 60_000,
        startRequestedAt = 0,
        refreshDeadlineAt = 0
    )

    assertFalse(ServiceHealthEvaluator.shouldRestart(state, NOW))
}

@Test
fun `overdue schedule and stale heartbeat restart outside grace windows`() {
    val state = ServiceHealthState(
        expectedNextAt = NOW - SCHEDULE_GRACE_MS - 1,
        lastHeartbeat = NOW - HEARTBEAT_GRACE_MS - 1,
        startRequestedAt = NOW - STARTUP_GRACE_MS - 1,
        refreshDeadlineAt = NOW - 1
    )

    assertTrue(ServiceHealthEvaluator.shouldRestart(state, NOW))
}

@Test
fun `ordinary watchdog does not clear a valid expected refresh`() {
    scheduler.recordSchedule(context, 1800, NOW + 1_800_000, "alarm")
    receiver.onReceive(context, Intent(StaticWidgetProvider.ACTION_WATCHDOG))
    assertEquals(NOW + 1_800_000, scheduler.getState(context).expectedNextAt)
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.RefreshSchedulerTest" --tests "com.balancesentinel.app.receiver.KeepAliveReceiverTest" --tests "com.balancesentinel.app.receiver.BootReceiverTest" --rerun-tasks
```

Expected: FAIL because stale heartbeat alone marks the service dead and Widget clears `expectedNextAt` before deciding whether to refresh.

- [ ] **Step 3: Persist independent schedule/heartbeat/refresh state**

```kotlin
const val SCHEDULE_GRACE_MS = 30_000L
const val HEARTBEAT_GRACE_MS = 120_000L
const val STARTUP_GRACE_MS = 10_000L

data class ServiceHealthState(
    val expectedNextAt: Long,
    val lastHeartbeat: Long,
    val startRequestedAt: Long,
    val refreshDeadlineAt: Long
)

fun shouldRestart(state: ServiceHealthState, now: Long): Boolean =
    state.expectedNextAt > 0 &&
        now > state.expectedNextAt + SCHEDULE_GRACE_MS &&
        (state.lastHeartbeat <= 0 || now > state.lastHeartbeat + HEARTBEAT_GRACE_MS) &&
        (state.startRequestedAt <= 0 || now > state.startRequestedAt + STARTUP_GRACE_MS) &&
        (state.refreshDeadlineAt <= 0 || now > state.refreshDeadlineAt)
```

Before a service refresh, persist a deadline of `now + 30_000L + accountCount * 20_000L`; clear it in `finally`. Heartbeat does not replace schedule state. Keepalive reads this combined evaluator.

- [ ] **Step 4: Separate manual refresh from watchdog**

Use `ACTION_REFRESH_NOW = "com.balancesentinel.app.WIDGET_REFRESH_NOW"` only for the button and `ACTION_WATCHDOG = "com.balancesentinel.app.WIDGET_WATCHDOG"` for alarms. Watchdog leaves a valid schedule untouched; only when `shouldRestart` is true does it invoke `refreshAll(WATCHDOG)` and request service restart. Manual action invokes `refreshAll(WIDGET)` immediately.

- [ ] **Step 5: Use a compliant foreground-service starter**

```kotlin
sealed interface ServiceStartResult {
    data object Started : ServiceStartResult
    data class Deferred(val retryAt: Long, val reason: String) : ServiceStartResult
    data class Failed(val reason: String) : ServiceStartResult
}
```

`ForegroundServiceStarter.start()` calls `ContextCompat.startForegroundService`, marks the start request first, catches `ForegroundServiceStartNotAllowedException`/`SecurityException`, writes a typed bounded diagnostic and schedules the next allowed alarm retry. Boot, Keepalive, Widget takeover and task-removal restart all use this helper; remove `BootReceiver.startService` and silent catches.

- [ ] **Step 6: Run GREEN receiver/service tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.RefreshSchedulerTest" --tests "com.balancesentinel.app.receiver.*" --tests "com.balancesentinel.app.service.ForegroundServiceStarterTest" --rerun-tasks
```

Expected: PASS; a 30-minute interval is not restarted at two minutes, while a truly overdue and heartbeat-stale service is restarted once.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "fix: distinguish refresh schedule from service health"
```

---

### Task 9: 按账户与币种隔离全部告警状态

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/WidgetPrefs.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/AlertChecker.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/NotificationHelper.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/receiver/SnoozeReceiver.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/AccountLifecycleManager.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/WidgetPrefsTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/AlertCheckerTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/NotificationHelperTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/receiver/SnoozeReceiverTest.kt`

**Interfaces:**
- Consumes: existing per-currency enable settings and Task 3 committer calls.
- Produces: `AlertIdentity(accountId,currency)`, pair-keyed anchors/dedup state and stable pair-derived notification/PendingIntent IDs.

- [ ] **Step 1: Write failing cross-currency isolation tests**

```kotlin
@Test
fun `same account currencies keep independent low balance dedup state`() {
    enable("acct", "CNY")
    enable("acct", "USD")

    assertTrue(AlertChecker.check(context, "acct", "1", "CNY", "A"))
    assertTrue(AlertChecker.check(context, "acct", "1", "USD", "A"))
    assertEquals(1f, prefs.getLastAlertedBalance("acct", "CNY"))
    assertEquals(1f, prefs.getLastAlertedBalance("acct", "USD"))
}

@Test
fun `notification and pending intent identities differ by currency`() {
    assertNotEquals(helper.alertNotificationId("acct", "CNY"), helper.alertNotificationId("acct", "USD"))
    assertNotEquals(helper.deepLinkRequestCode("acct", "CNY"), helper.deepLinkRequestCode("acct", "USD"))
    assertNotEquals(helper.snoozeRequestCode("acct", "CNY"), helper.snoozeRequestCode("acct", "USD"))
}
```

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.data.repository.NotificationHelperTest" --tests "com.balancesentinel.app.data.repository.WidgetPrefsTest" --rerun-tasks
```

Expected: FAIL because low-balance, change anchors, dedup timestamps and IDs currently use only `accountId`.

- [ ] **Step 3: Canonicalize pair identity and storage keys**

```kotlin
data class AlertIdentity(val accountId: String, val currency: String) {
    val normalizedCurrency: String = currency.uppercase(Locale.ROOT)
    val storageSuffix: String = "${accountId}_${normalizedCurrency}"
}
```

Change these methods to require both account and currency: last alerted balance, previous balance/time, last change alerted balance/time, balance/change enable switch and removal. Snooze remains account-wide, but its PendingIntent request code includes currency so simultaneous actions do not overwrite each other.

One-time migration preserves existing per-currency enable switches, removes legacy account-only balance/change anchors and dedup values, and marks the migration complete. The first post-upgrade refresh establishes independent anchors. `removeAccountAlertState` scans and removes all pair keys and notification selections for that account. Key rotation copies enable settings to the new ID but resets anchors/dedup state.

- [ ] **Step 4: Derive stable IDs from the full pair**

Use SHA-256 of `"kind\u0000accountId\u0000CURRENCY"`; read the first four bytes, clear the sign bit and add a kind-specific base. Use the same helper for alert ID, change ID, deep-link request code and snooze request code. Notification content and deep links continue carrying both account and currency.

- [ ] **Step 5: Run GREEN tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.data.repository.NotificationHelperTest" --tests "com.balancesentinel.app.data.repository.WidgetPrefsTest" --tests "com.balancesentinel.app.receiver.SnoozeReceiverTest" --rerun-tasks
```

Expected: PASS; CNY actions and state cannot overwrite USD for the same account.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "fix: isolate alerts by account and currency"
```

---

### Task 10: 完整重算历史摘要并验证后安全删除原始记录

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/RawRecordStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/DailySummaryStore.kt`
- Rewrite: `app/src/main/java/com/balancesentinel/app/data/repository/CleanupScheduler.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/refresh/RefreshResultCommitter.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/RawRecordStoreTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/DailySummaryStoreTest.kt`
- Rewrite: `app/src/test/java/com/balancesentinel/app/data/repository/CleanupSchedulerTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/refresh/RefreshResultCommitterTest.kt`

**Interfaces:**
- Consumes: Task 3 response-completion timestamp and batched history write, existing `RecordAggregator.aggregate`.
- Produces: synchronous `StoreWriteResult`, `DailySummaryStore.replaceForDate`, exact-snapshot raw-record deletion and typed `CleanupReport`.

- [ ] **Step 1: Write failing late-record, persistence-failure and exact-delete tests**

```kotlin
@Test
fun `late record replaces an existing pair summary instead of being skipped`() = runTest {
    val early = record("acct", "USD", at("2026-07-31T01:00:00Z"), 10f)
    val late = record("acct", "USD", at("2026-07-31T23:00:00Z"), 7f)
    RawRecordStore.addRecords(context, listOf(early))
    scheduler.runCleanup(context, now = at("2026-08-01T01:00:00Z"))
    RawRecordStore.addRecords(context, listOf(late))

    scheduler.runCleanup(context, now = at("2026-08-01T02:00:00Z"))

    val summary = DailySummaryStore.getSummaries(context).single()
    assertEquals(2, summary.sampleCount)
    assertEquals(7f, summary.close)
}

@Test
fun `failed summary commit retains every source record`() = runTest {
    val source = listOf(record("acct", "CNY", OLD_TIME, 12f))
    rawStore.addRecords(source)
    summaryStore.failNextWrite()

    val report = scheduler.runCleanup(now = NOW)

    assertTrue(report.failures.isNotEmpty())
    assertEquals(source, rawStore.getAllRecords())
}

@Test
fun `record arriving during archival survives exact snapshot deletion`() = runTest {
    val source = record("acct", "CNY", OLD_TIME, 12f)
    val late = record("acct", "CNY", OLD_TIME + 1, 11f)
    rawStore.addRecords(listOf(source))
    summaryStore.afterCommit = { rawStore.addRecords(listOf(late)) }

    scheduler.runCleanup(now = NOW)

    assertEquals(listOf(late), rawStore.getAllRecords())
}
```

Add a committer test whose fake clock advances while the network result completes; assert every `RawRecord.timestamp` is the completion time, not `RefreshRequest.startedAt`.

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.RawRecordStoreTest" --tests "com.balancesentinel.app.data.repository.DailySummaryStoreTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest" --rerun-tasks
```

Expected: FAIL because summaries are insert-only, cleanup skips existing pairs, persistence uses asynchronous `apply()`, and deletion is not conditioned on verified readback.

- [ ] **Step 3: Make history writes synchronous and reportable**

```kotlin
sealed interface StoreWriteResult {
    data class Written(val itemCount: Int) : StoreWriteResult
    data class Failed(val operation: String, val reason: String) : StoreWriteResult
}

fun addRecords(context: Context, records: List<RawRecord>): StoreWriteResult
fun replaceForDate(
    context: Context,
    date: String,
    summaries: List<DailySummary>
): StoreWriteResult
fun removeExact(
    context: Context,
    snapshot: List<RawRecord>
): StoreWriteResult
```

Every mutating store method runs under its store-wide lock and uses synchronous `commit()`. `replaceForDate` removes only the `(date, accountId, currency)` keys present in the incoming recomputation, adds their replacements, sorts once and commits once. It preserves summaries for unrelated pairs and dates. Failure details are bounded and contain no serialized data.

`removeExact` treats the snapshot as a multiset of full `RawRecord` values. It removes no more copies than were observed and never uses only `(accountId,timestamp)` as identity, so another currency or a late record cannot be deleted accidentally.

Replace shared mutable `SimpleDateFormat` instances with `java.time` conversion using an injected/system `ZoneId`; the same zone determines record dates and `LocalDate.now`. Tests fix the zone explicitly and cover a record on each side of local midnight.

- [ ] **Step 4: Implement recompute, readback verification and conditional deletion**

```kotlin
data class CleanupReport(
    val archivedDates: Set<String>,
    val deletedRecordCount: Int,
    val retainedRecordCount: Int,
    val failures: List<CleanupFailure>
)

data class CleanupFailure(val date: String, val stage: CleanupStage, val reason: String)
enum class CleanupStage { READ_SOURCE, WRITE_SUMMARY, VERIFY_SUMMARY, DELETE_SOURCE }
```

For each non-today date, `CleanupScheduler` must execute exactly:

1. Read one immutable snapshot of all current raw records for the date.
2. Recompute every pair in that snapshot with `RecordAggregator.aggregate`.
3. Replace those pair summaries in one synchronous write.
4. Read the persisted summaries back and verify exact source keys plus each pair's `date`, `accountId`, normalized `currency` and `sampleCount` against the source grouping.
5. Delete only the immutable source snapshot with `removeExact` when verification succeeds and every snapshot record is older than 24 hours.

Continue processing other dates after a date-level failure and return failures in `CleanupReport`; do not swallow the whole run. Run continuity filling only after all archive attempts and make its write reportable as well. A later run sees any newly arrived record, recomputes the pair and replaces the prior summary.

`RefreshResultCommitter` calls its injected wall clock after the provider response is available and immediately before building the batch of `RawRecord`s. Map `StoreWriteResult.Failed` to `RefreshFailure.PersistenceFailure` and stop later history-dependent side effects.

- [ ] **Step 5: Run GREEN plus mutation cases**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.RawRecordStoreTest" --tests "com.balancesentinel.app.data.repository.DailySummaryStoreTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --tests "com.balancesentinel.app.data.engine.RecordAggregatorTest" --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest" --rerun-tasks
```

Expected: PASS; changing replacement back to insert-only, removing readback verification, weakening record identity, or deleting after a failed commit makes a focused test fail.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "fix: verify summaries before pruning raw history"
```

---

### Task 11: 限制调试数据并恢复 Release 发布门禁

**Files:**
- Create: `app/src/main/java/com/balancesentinel/app/data/debug/SensitiveDataRedactor.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/debug/DebugCapture.kt`
- Create: `app/src/main/java/com/balancesentinel/app/data/debug/DebugReportFormatter.kt`
- Rewrite: `app/src/main/java/com/balancesentinel/app/data/debug/DebugInterceptor.kt`
- Rewrite: `app/src/main/java/com/balancesentinel/app/data/debug/ApiDebugStore.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/DeepSeekApiService.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/balance/BalanceQueryService.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/providers/DeepSeekProvider.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/providers/OpenAiCompatibleProvider.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/console/DebugLogger.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/util/Logger.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/LogExporter.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/components/DebugDialog.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/console/ConsoleComponents.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/console/ConsoleScreen.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/CrashLogger.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/build.gradle.kts`
- Delete: `app/lint-baseline.xml`
- Create: `app/src/test/java/com/balancesentinel/app/data/debug/SensitiveDataRedactorTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/debug/DebugCaptureTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/debug/ApiDebugStoreTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/debug/DebugInterceptorTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/data/debug/DebugReportFormatterTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/util/LoggerTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/data/repository/LogExporterTest.kt`
- Modify: `app/src/test/java/com/balancesentinel/app/CrashLoggerTest.kt`

**Interfaces:**
- Consumes: OkHttp request/response streams, `BuildConfig.DEBUG`, Compose clipboard/file commands and Android lint.
- Produces: one `SensitiveDataRedactor`, bounded `DebugCapture`, `DebugReportFormatter`, global byte-budget LRU store, release-disabled capture policy and resettable crash handler.

- [ ] **Step 1: Write failing truncation, redaction, LRU and build-policy tests**

```kotlin
@Test
fun `capture bounds each body in utf8 bytes and marks truncation`() {
    val body = "凭据".repeat(30_000)
    val captured = DebugCapture.captureUtf8(
        body.byteInputStream(Charsets.UTF_8),
        maxBytes = 64 * 1024
    )

    assertTrue(captured.truncated)
    assertTrue(captured.text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
}

@Test
fun `redactor removes sensitive headers json fields query values and free text`() {
    val headers = SensitiveDataRedactor.redactHeaders(
        headersOf("Cookie", "sid=cookie-value", "X-Api-Key", "header-value")
    )
    val body = SensitiveDataRedactor.redactText(
        """{"refresh_token":"body-token","nested":{"secretKey":"body-key"}}"""
    )
    val url = SensitiveDataRedactor.redactUrl(
        "https://example.com/path?access_token=query-token".toHttpUrl()
    )

    listOf("cookie-value", "header-value", "body-token", "body-key", "query-token").forEach {
        assertFalse(headers.toString().contains(it) || body.contains(it) || url.contains(it))
    }
    assertTrue(headers.toString().contains("[REDACTED]"))
    assertTrue(body.contains("[REDACTED]"))
    assertTrue(url.contains("[REDACTED]"))
}

@Test
fun `global store evicts oldest entries until under two mibibytes`() {
    repeat(40) { store.addEntry(entry(accountId = "a$it", responseBytes = 64 * 1024)) }

    assertTrue(store.currentBytes <= 2 * 1024 * 1024)
    assertFalse(store.getAccountIds().contains("a0"))
}

@Test
fun `release build policy never installs debug capture`() {
    assertFalse(DebugCapturePolicy.enabled(debuggable = false))
    assertEquals(BuildConfig.DEBUG, DebugCapturePolicy.enabled())
}
```

Add interceptor tests for `Authorization`, `Cookie`, `Set-Cookie`, API/secret/token JSON keys, a 64 KiB request, a 64 KiB response, an error response, a one-shot request body and response preservation. Add report/clipboard formatter tests proving API entries, Console session data, ordinary debug logs, cookies and localStorage all pass through the same redactor after formatting. Assert the exported file contains redaction markers and none of the seeded secret values.

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.debug.*" --tests "com.balancesentinel.app.data.util.LoggerTest" --tests "com.balancesentinel.app.data.repository.LogExporterTest" --tests "com.balancesentinel.app.CrashLoggerTest" --rerun-tasks
```

Expected: FAIL because response bodies are fully consumed, only `Authorization` is partially masked, response/error/cookies remain raw, storage has no global byte budget, and CrashLogger retains process-global test state.

- [ ] **Step 3: Implement one bounded capture and redaction boundary**

```kotlin
const val MAX_CAPTURE_BYTES = 64 * 1024L
const val MAX_DEBUG_STORE_BYTES = 2 * 1024 * 1024L

data class CapturedText(val text: String, val truncated: Boolean, val byteCount: Long)

object DebugCapture {
    internal fun captureUtf8(input: InputStream, maxBytes: Int): CapturedText
    fun captureRequest(body: RequestBody?): CapturedText?
    fun captureResponse(response: Response): CapturedText
}

object SensitiveDataRedactor {
    fun redactUrl(url: HttpUrl): String
    fun redactHeaders(headers: Headers): Map<String, String>
    fun redactText(text: String): String
    fun redactForClipboard(text: String): String = redactText(text)
}
```

Request capture skips duplex and one-shot bodies with a fixed marker. Other bodies write through a bounded sink which retains at most 64 KiB while discarding overflow without allocating the full body. Response capture uses `peekBody(MAX_CAPTURE_BYTES + 1)` and never calls `response.body.string()`, so the caller receives the original streaming body unchanged. Truncate on bytes and decode valid UTF-8 with a replacement boundary. Keep `CapturedText.text` inside the byte limit; formatters append a localized/constant `[TRUNCATED]` marker only when `truncated=true`.

Header names are compared case-insensitively and redact at least Authorization, Proxy-Authorization, Cookie, Set-Cookie, X-Api-Key, API-Key, Secret-Key and token-bearing headers. URL query values and nested JSON/form/text fields matching API key, secret, access token or refresh token names are fully replaced rather than partially revealed. Run bounded error summaries, exception text, non-sensitive script metadata and all Debug/Console clipboard or file output through the same redactor.

Do not retain custom script source or its first characters in `ApiDebugEntry`: replace `scriptPreview` with non-sensitive script length plus SHA-256 fingerprint. `DebugReportFormatter` is the only formatter for individual entries, bulk clipboard text and files; it performs a final redaction pass even though entries are already sanitized. `Logger`, `DebugLogger`, `CrashLogger` and `LogExporter` use the same redactor so non-`sk-` credentials cannot bypass the API debug boundary.

- [ ] **Step 4: Enforce a global LRU byte budget and Debug-only installation**

`ApiDebugStore` stores entries in global insertion/access order, computes UTF-8 bytes for every retained string and header value, and evicts oldest entries across accounts until `currentBytes <= MAX_DEBUG_STORE_BYTES`. Keep the existing per-account count ceiling only as a secondary bound. Sanitization happens before insertion; the store rejects or sanitizes any direct caller so bypassing the interceptor cannot persist secrets.

Delete the duplicate Console `ApiLogEntry` retention path. Console API interception emits sanitized, bounded `ApiDebugEntry` values under a namespaced key such as `console:<platformId>` into the same `ApiDebugStore`; in-memory Console session state remains available for authentication but its debug projection replaces every Cookie/localStorage value. Console display, clipboard and `saveToFile` consume only this projection and `DebugReportFormatter`, never the raw session maps. Disable the Console interception/debug projection entirely when `DebugCapturePolicy` is false.

Every OkHttp builder installs `DebugInterceptor` only through:

```kotlin
object DebugCapturePolicy {
    fun enabled(debuggable: Boolean = BuildConfig.DEBUG): Boolean = debuggable
}
```

Guard DeepSeek, strict balance and OpenAI-compatible clients with this policy. Delete `HomeViewModel`'s manual debug-entry construction as required by Task 4. Tests inspect each constructed client in both variants: Debug has exactly one capture interceptor and Release has none. In Release, no interceptor is installed and no manual `ApiDebugStore.addEntry` path remains. The debug UI may compile in Release but must remain empty because capture is absent.

- [ ] **Step 5: Restore manifest, lint, translation and source-health gates**

Delete the nonexistent `.ui.console.ConsoleWebViewActivity` declaration from the Manifest. Replace the literal NUL byte in `UsageScriptExecutor.kt` with the visible Kotlin escape `\u0000`. Set `lint.abortOnError = true`, remove the baseline assignment, delete `app/lint-baseline.xml`, and fix every resulting lint error. Move new and existing hard-coded user-visible copy touched by this work into both `values/strings.xml` and `values-en/strings.xml`; add the currently missing Console clear translations.

Do not silence new findings with `tools:ignore`, a new baseline, `abortOnError=false`, or broad lint disables. Warnings that are intentionally deferred remain visible and documented, but the lint error count must be zero.

Add `CrashLogger.resetForTests()` as an internal synchronized test seam: restore the handler captured by `install`, clear `appRef` and breadcrumbs, and make repeated `install` calls preserve the true original handler. `CrashLoggerTest` records the pre-test handler and calls reset in `tearDown`; assert no test changes the process default handler after completion.

- [ ] **Step 6: Run GREEN, Release policy and lint checks**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.debug.*" --tests "com.balancesentinel.app.data.util.LoggerTest" --tests "com.balancesentinel.app.data.repository.LogExporterTest" --tests "com.balancesentinel.app.CrashLoggerTest" --rerun-tasks
.\gradlew.bat testReleaseUnitTest --tests "com.balancesentinel.app.data.debug.*" --rerun-tasks
.\gradlew.bat lintDebug lintRelease assembleDebug assembleRelease --rerun-tasks
```

Expected: tests PASS; Debug capture is bounded and sanitized, Release policy is disabled, both APKs build, lint reports zero errors and no baseline file is recreated.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main app/src/test app/build.gradle.kts
git add -u app/lint-baseline.xml
git commit -m "fix: bound debug capture and restore release gates"
```

---

### Task 12: 全量回归、设备验证、覆盖率与最终代码审查

**Files:**
- Modify as needed: tests or production files implicated by failures from Tasks 1-11
- Modify: `README.md`
- Modify: `PROJECT_INDEX.md`
- Modify: `TEST_REPORT.md`
- Modify: `RELEASE_REVIEW_REPORT.md`
- Modify if behavior/privacy copy changed: `PRIVACY_POLICY.md`

**Interfaces:**
- Consumes: the complete Tasks 1-11 diff and all Gradle verification tasks.
- Produces: reproducible test/build/lint evidence, documented device-test status and a reviewed final diff with no open blocking/high findings.

- [ ] **Step 1: Run focused integration suites with task outputs forced fresh**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.*" --tests "com.balancesentinel.app.data.api.balance.*" --tests "com.balancesentinel.app.data.repository.*" --tests "com.balancesentinel.app.data.console.*" --tests "com.balancesentinel.app.receiver.*" --tests "com.balancesentinel.app.service.*" --tests "com.balancesentinel.app.widget.*" --rerun-tasks
```

Expected: all focused integration suites PASS with no cached task result.

- [ ] **Step 2: Run the complete Debug JVM suite twice independently**

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

Expected: both complete runs finish with zero failures. Preserve both Gradle reports as verification evidence; a pass followed by a failure is treated as a race and must be fixed before continuing.

- [ ] **Step 3: Run Release, lint, packaging and coverage gates**

First discover the exact Kover task name with `gradlew tasks --all`, then run the matching XML/HTML report and verification task. The expected command shape is:

```powershell
.\gradlew.bat testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease koverXmlReportDebug koverVerifyDebug --rerun-tasks
```

Expected: Debug and Release unit tests/builds PASS, lint has zero errors with `abortOnError=true`, Kover verification passes, and no `lint-baseline.xml` exists. Inspect merged manifests and APK contents to confirm the nonexistent Console activity is absent and Release does not retain an active debug-capture installation path.

- [ ] **Step 4: Run device/security tests when an API 35 device is available**

```powershell
adb devices
.\gradlew.bat connectedDebugAndroidTest --rerun-tasks
```

The device run must cover backup preview/no-write behavior, exact-origin WebView injection/logout, boot restore, foreground-service start restrictions, widget manual/watchdog separation and long refresh intervals. If no API 35 device is connected, record each unexecuted class and scenario explicitly in `TEST_REPORT.md`; do not represent JVM/Robolectric coverage as device verification.

- [ ] **Step 5: Perform a complete diff review and close every blocking/high finding**

Review the full diff from baseline `0e858065053f33b68a4f2173358ab97482f0c772`, not only the latest commit. Trace the changed refresh, account, script, backup, Console, service, alert, history and debug call chains with codebase-memory, then manually inspect security and persistence boundaries.

Check at minimum:

- No credential, Cookie, raw response body or script secret reaches stable errors, logs, backups or clipboard.
- Every refresh entry point uses the shared coordinator; stale/failed results cannot mutate caches, history or alerts.
- Unsupported providers make zero guessed requests; six native contracts have fixture coverage.
- Import apply is impossible before preview, and destructive replace requires complete credentials plus two confirmations.
- URL/origin/DNS/redirect checks cannot be bypassed by host substrings, user info, alternate ports or private addresses.
- Account lifecycle changes migrate or remove every owned data set under race-safe mutations.
- Summary deletion occurs only after synchronous write and exact readback coverage.

Fix all blocking/high findings with a new observed RED test followed by GREEN. Re-run the impacted focused suite after each fix.

- [ ] **Step 6: Run repository hygiene and source-integrity checks**

```powershell
git diff --check 0e858065053f33b68a4f2173358ab97482f0c772
rg -n "T[B]D|T[O]DO|F[I]XME|implement[ ]later|similar[ ]to" app/src docs/superpowers/plans/2026-08-01-wallet-sentinel-hardening.md
```

Also scan tracked source bytes for NUL, verify all new Chinese/English resource keys are paired, ensure generated APK/test/report outputs are not staged, and review `git status --short` for unrelated user changes. Any deliberate deferred-work marker must have a documented owner and cannot cover an acceptance criterion.

- [ ] **Step 7: Update release and verification documentation**

Update `README.md` and `PROJECT_INDEX.md` for the new shared refresh/security architecture. Record exact commands, pass counts, lint/Kover outcomes and device-test status in `TEST_REPORT.md`. Update `RELEASE_REVIEW_REPORT.md` with the final findings and residual risks; update `PRIVACY_POLICY.md` only if the actual data-handling behavior changes its promises.

- [ ] **Step 8: Final verification commit**

```powershell
git add README.md PROJECT_INDEX.md TEST_REPORT.md RELEASE_REVIEW_REPORT.md PRIVACY_POLICY.md app/src/main app/src/test app/src/androidTest app/build.gradle.kts
git commit -m "test: complete wallet sentinel hardening verification"
```

Before declaring completion, run `git status --short`, inspect `git diff HEAD^`, and report any skipped device tests or residual non-blocking warnings explicitly.
