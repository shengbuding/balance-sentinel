# 全项目测试实施计划

> **For agentic workers:** Use superpowers:subagent-driven-development (recommended) to implement this plan task-by-task.

**Goal:** 从 15.8% 行覆盖率提升到 60%+，覆盖所有核心逻辑和关键边界

**Architecture:** 四层递进 — 纯逻辑先补齐 → Store/Repository → ViewModel → Service/Receiver/Screen。每层独立可测，不依赖上层。

**Tech Stack:** JUnit 4, Robolectric, MockK, kotlinx-coroutines-test

## Global Constraints

- 目标覆盖率 60%+（行），不强求 100%
- 不测纯样板代码：CustomIcons、Theme、CrashLogger、Widget 静态 Provider 子类
- API Key 加密存储（EncryptedSharedPreferences）不可在 Robolectric 中使用，需用 SharedPreferences mock
- 所有测试放在 `app/src/test/` 下，测试文件命名 `*Test.kt`
- 使用与现有测试一致的包名：`com.balancesentinel.app.<package>`

---

### Task 1: FormatUtils 测试（第 1 层 — 纯工具类）

**Files:**
- Create: `app/src/test/java/com/balancesentinel/app/util/FormatUtilsTest.kt`

**Interfaces:**
- Consumes: `FormatUtils.formatAmount()`, `FormatUtils.currencySymbol()`, `FormatUtils.formatInterval()`, `FormatUtils.formatFullTime()`
- Produces: (无下游依赖)

- [ ] **Step 1: 创建测试文件，覆盖所有纯函数**

纯函数测试：`formatAmount`（正常/负数/非法输入）、`currencySymbol`（CNY/USD/EUR/未知）、`formatInterval`（秒/分钟/混合）、`formatFullTime`（有效时间戳）

- [ ] **Step 2: 运行验证**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*FormatUtilsTest'
```

- [ ] **Step 3: 提交**

```bash
git add app/src/test/java/com/balancesentinel/app/util/FormatUtilsTest.kt
git commit -m "test: add FormatUtils unit tests"
```

---

### Task 2: RecordAggregator 边界测试（第 1 层 — 引擎补强）

**Files:**
- Modify: `app/src/test/java/com/balancesentinel/app/data/engine/RecordAggregatorTest.kt`

**Interfaces:**
- Consumes: `RecordAggregator.aggregateDailySummary()`, `RecordAggregator.detectTopUps()`
- Produces: (无下游依赖)

- [ ] **Step 1: 添加边界用例**

空记录列表、单条记录、跨天充值检测、grantedBalance 变化、大额消耗

- [ ] **Step 2: 运行验证**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*RecordAggregatorTest'
```

- [ ] **Step 3: 提交**

---

### Task 3: ApiKeyManager 测试（第 2 层 — Store）

**Files:**
- Create: `app/src/test/java/com/balancesentinel/app/data/repository/ApiKeyManagerTest.kt`

**Approach:** 不使用实际的 EncryptedSharedPreferences，改用 `context.getSharedPreferences("test_prefs", MODE_PRIVATE)` 直接读写，绕过加密层。需要重构 `ApiKeyManager` 使其可注入 SharedPreferences。

- [ ] **Step 1: 重构 ApiKeyManager 支持可注入 prefs**

添加 test-only 构造函数 `constructor(appContext: Context, prefs: SharedPreferences)` 

- [ ] **Step 2: 创建测试**

测 `computeId` 确定性、`addAccount` 去重/新增、`removeAccount`、`renameAccount`、`clearAll`、`migrateLegacyKeyIfNeeded`、`getAccounts` 空/异常 JSON

- [ ] **Step 3: 运行验证**

- [ ] **Step 4: 提交**

---

### Task 4: RefreshStatsStore 测试（第 2 层 — Store）

**Files:**
- Create: `app/src/test/java/com/balancesentinel/app/data/repository/RefreshStatsStoreTest.kt`

- [ ] **Step 1: 创建测试**

测 `recordSuccess`/`recordFailure`/`recordSkipped` 计数、环形缓冲区超限覆盖、`getStats` 统计正确性、`reset` 清零

- [ ] **Step 2: 运行验证**

- [ ] **Step 3: 提交**

---

### Task 5: HomeViewModel 测试（第 3 层 — ViewModel）

**Files:**
- Create: `app/src/test/java/com/balancesentinel/app/ui/viewmodel/HomeViewModelTest.kt`

**Approach:** MockK 模拟 ApiKeyManager、BalanceRepository、AlertChecker、WidgetPrefs 等所有依赖。

- [ ] **Step 1: 创建测试**

测 `loadAccounts`、`refreshBalance` 成功/失败、`addAccount`、`removeAccount`、`renameAccount`、初始状态

- [ ] **Step 2: 运行验证**

- [ ] **Step 3: 提交**

---

### Task 6: Receiver 测试（第 4 层）

**Files:**
- Create: `app/src/test/java/com/balancesentinel/app/receiver/BootReceiverTest.kt`
- Create: `app/src/test/java/com/balancesentinel/app/receiver/MidnightReceiverTest.kt`

- [ ] **Step 1: 创建测试**

测 `onReceive` 分支逻辑：schedule 调用、条件判断

- [ ] **Step 2: 运行验证**

- [ ] **Step 3: 提交**

---

### Task 7: 覆盖率验证与收尾

- [ ] **Step 1: 运行全量测试 + Kover 报告**

```bash
./gradlew testDebugUnitTest koverHtmlReportDebug --no-daemon
```

- [ ] **Step 2: 验证覆盖率 >= 60%**

- [ ] **Step 3: 提交最终修改**

