# 洞察页 — 消耗图表切换 + 历史日汇总 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在洞悉页面长期趋势模块中添加余额/消耗图表切换，并在下方新增历史日汇总列表卡片。

**Architecture:** 纯 UI 层扩展，数据层仅 DailyPoint 模型新增 open/sampleCount 两个字段。图表切换在 UI 层切换 Y 轴数据源；历史汇总卡片复用 DailyOutput.dailyPoints 数据，不做额外存储查询。

**Tech Stack:** Kotlin + Jetpack Compose + Material 3 · JUnit 4 + Robolectric · SharedPreferences + JSON

## Global Constraints

- 会计恒等式: consumed/toppedUp/granted 同源检测，禁止混用引擎
- 历史冻结: 非今日 DailySummary 生成后永不修改
- 向后兼容: JSON `ignoreUnknownKeys = true`；新字段默认值
- 零第三方追踪: 无 Firebase/分析/广告 SDK
- 项目路径: `C:\Users\Administrator\DeepSeekBalance\`
- 构建: `./gradlew.bat assembleDebug --no-daemon`
- 测试: `./gradlew.bat testDebugUnitTest --no-daemon`

---

### Task 1: 扩展 DailyPoint 模型

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/engine/DailyModels.kt`

**Interfaces:**
- Consumes: (none, first task)
- Produces: `DailyPoint(open: Float = 0f, sampleCount: Int = 0, ...)` — 新增两个带默认值的字段，向后兼容

- [ ] **Step 1: 修改 DailyPoint 数据类**

在 `DailyPoint` 中添加 `open` 和 `sampleCount` 字段，带默认值：

```kotlin
data class DailyPoint(
    val date: String,
    val balance: Float,
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val isGapFill: Boolean,
    val open: Float = 0f,           // 新增：开盘余额
    val sampleCount: Int = 0        // 新增：当日采样次数
)
```

- [ ] **Step 2: 编译验证**

```bash
cd /c/Users/Administrator/DeepSeekBalance && export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10" && export ANDROID_HOME="$HOME/Android/Sdk" && ./gradlew.bat compileDebugKotlin --no-daemon
```

- [ ] **Step 3: 运行现有测试确保向后兼容**

```bash
./gradlew.bat testDebugUnitTest --no-daemon
```

预期: 全部通过（新字段有默认值，现有构造调用不受影响）

- [ ] **Step 4: 提交**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git add app/src/main/java/com/balancesentinel/app/data/engine/DailyModels.kt && git commit -m "feat: DailyPoint 新增 open 和 sampleCount 字段"
```

---

### Task 2: DailyEngine 填充新字段

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/engine/DailyEngine.kt:60-98`

**Interfaces:**
- Consumes: `DailyPoint(open, sampleCount)` from Task 1
- Produces: 所有 DailyEngine 构造的 DailyPoint 现在携带 open 和 sampleCount

- [ ] **Step 1: 写失败测试**

在 `DailyEngineTest.kt` 中添加：

```kotlin
@Test
fun `daily point includes open and sampleCount from summary`() {
    val summary = DailySummary(
        accountId = "acc1", date = "2026-07-04", currency = "CNY",
        open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
        granted = 0f, avgBalance = 95f, sampleCount = 5,
        toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
    )
    val input = DailyInput(listOf(summary), emptyList(), "CNY", null, 7)
    val output = DailyEngine.compute(input)

    val point = output.dailyPoints[0]
    assertEquals(100f, point.open, 0.01f)
    assertEquals(5, point.sampleCount)
}

@Test
fun `today point includes open and sampleCount from raw records`() {
    val now = System.currentTimeMillis()
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())
    val todayRecords = listOf(
        RawRecord("acc1", now - 2000L, "CNY", 100f, 0f, 100f),
        RawRecord("acc1", now - 1000L, "CNY", 95f, 0f, 95f),
        RawRecord("acc1", now, "CNY", 90f, 0f, 90f)
    )
    val input = DailyInput(emptyList(), todayRecords, "CNY", null, 7)
    val output = DailyEngine.compute(input)

    val point = output.dailyPoints[0]
    assertEquals(100f, point.open, 0.01f)    // first record's totalBalance
    assertEquals(3, point.sampleCount)        // 3 records
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /c/Users/Administrator/DeepSeekBalance && export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10" && export ANDROID_HOME="$HOME/Android/Sdk" && ./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.DailyEngineTest"
```

预期: 新增的两个测试 FAIL（open 和 sampleCount 为默认值 0）

- [ ] **Step 3: 在 DailyEngine 中填充新字段**

修改 `DailyEngine.compute()` 中三处构造 DailyPoint 的地方：

**位置 1 — todayPoint from raw records（约第 34 行）：**

```kotlin
val todayPoint: DailyPoint? = if (todayFiltered.size >= 1) {
    // ... existing code ...
    DailyPoint(
        date = today,
        balance = last.totalBalance,
        consumed = consumed,
        toppedUp = todayTopUp,
        granted = todayGrant,
        isGapFill = false,
        open = first.totalBalance,       // 新增
        sampleCount = todayFiltered.size  // 新增
    )
} else null
```

**位置 2 — summary 转 DailyPoint（约第 82 行）：**

```kotlin
DailyPoint(
    summary.date, summary.close, summary.consumed,
    summary.toppedUp, summary.granted, summary.sampleCount == 0,
    summary.open,        // 新增
    summary.sampleCount  // 新增
)
```

**位置 3 — 单点 todayPoint 当 window 为空时（约第 63 行）：**

```kotlin
// 此处的 todayPoint 已在位置 1 构造好，无需额外修改
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.DailyEngineTest"
```

预期: 全部 PASS（包括新增的两个测试）

- [ ] **Step 5: 运行全部单元测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon
```

预期: 214+ tests, 0 failures

- [ ] **Step 6: 提交**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git add app/src/main/java/com/balancesentinel/app/data/engine/DailyEngine.kt app/src/test/java/com/balancesentinel/app/data/engine/DailyEngineTest.kt && git commit -m "feat: DailyEngine 填充 DailyPoint.open 和 sampleCount"
```

---

### Task 3: InsightsViewModel 新增 chartMode 和历史汇总字段

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/ui/viewmodel/InsightsViewModel.kt`

**Interfaces:**
- Consumes: (none new, uses existing data)
- Produces:
  - `InsightsUiState.chartMode: String = "balance"` (或 "consumed")
  - `InsightsUiState.historyVisibleCount: Int = 7`
  - `InsightsUiState.expandedDate: String? = null`
  - `setChartMode(mode: String)` — 纯 UI 层切换，不触发 loadData
  - `loadMoreHistory()` — historyVisibleCount += 10, capped at dailyPoints.size
  - `toggleExpandDate(date: String)` — 切换展开/折叠

- [ ] **Step 1: 写 ViewModel 测试**

在 `InsightsViewModelTest.kt` 末尾添加：

```kotlin
// ── Chart mode switching ──

@Test
fun `setChartMode updates state without reloading data`() {
    val viewModel = InsightsViewModel(app)

    assertEquals("balance", viewModel.uiState.value.chartMode)

    viewModel.setChartMode("consumed")
    assertEquals("consumed", viewModel.uiState.value.chartMode)

    viewModel.setChartMode("balance")
    assertEquals("balance", viewModel.uiState.value.chartMode)
}

// ── History pagination ──

@Test
fun `historyVisibleCount starts at 7`() {
    val viewModel = InsightsViewModel(app)
    assertEquals(7, viewModel.uiState.value.historyVisibleCount)
}

@Test
fun `loadMoreHistory increases visible count by 10`() {
    val now = System.currentTimeMillis()
    // Insert 30 days of summaries to have enough data
    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    for (i in 1..30) {
        val date = dateFormat.format(java.util.Date(now - (30 - i + 1) * 24 * 3600_000L))
        DailySummaryStore.upsert(
            context,
            DailySummary(
                accountId = "acc1", date = date, currency = "CNY",
                open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
                granted = 0f, avgBalance = 95f, sampleCount = 5,
                toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
            )
        )
    }

    val viewModel = InsightsViewModel(app)
    kotlinx.coroutines.test.runBlocking { kotlinx.coroutines.delay(500) }  // wait for loadData

    assertEquals(7, viewModel.uiState.value.historyVisibleCount)

    viewModel.loadMoreHistory()
    assertEquals(17, viewModel.uiState.value.historyVisibleCount)

    viewModel.loadMoreHistory()
    assertEquals(27, viewModel.uiState.value.historyVisibleCount)

    // Capped at dailyPoints.size
    viewModel.loadMoreHistory()
    assertEquals(30, viewModel.uiState.value.historyVisibleCount)
}

// ── Expand/collapse ──

@Test
fun `toggleExpandDate toggles expanded date`() {
    val viewModel = InsightsViewModel(app)

    assertNull(viewModel.uiState.value.expandedDate)

    viewModel.toggleExpandDate("2026-07-01")
    assertEquals("2026-07-01", viewModel.uiState.value.expandedDate)

    viewModel.toggleExpandDate("2026-07-01")
    assertNull(viewModel.uiState.value.expandedDate)
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /c/Users/Administrator/DeepSeekBalance && export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10" && export ANDROID_HOME="$HOME/Android/Sdk" && ./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest"
```

- [ ] **Step 3: 修改 InsightsUiState 和 InsightsViewModel**

在 `InsightsUiState` 末尾（`isEmpty` 之前）添加：

```kotlin
val chartMode: String = "balance",
val historyVisibleCount: Int = 7,
val expandedDate: String? = null
```

在 `InsightsViewModel` 中添加方法：

```kotlin
fun setChartMode(mode: String) {
    _uiState.value = _uiState.value.copy(chartMode = mode)
}

fun loadMoreHistory() {
    val current = _uiState.value
    val maxDays = current.dailyOutput?.dailyPoints?.size ?: 0
    val next = (current.historyVisibleCount + 10).coerceAtMost(maxDays)
    _uiState.value = current.copy(historyVisibleCount = next)
}

fun toggleExpandDate(date: String) {
    val current = _uiState.value
    _uiState.value = current.copy(
        expandedDate = if (current.expandedDate == date) null else date
    )
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest"
```

- [ ] **Step 5: 提交**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git add app/src/main/java/com/balancesentinel/app/ui/viewmodel/InsightsViewModel.kt app/src/test/java/com/balancesentinel/app/ui/viewmodel/InsightsViewModelTest.kt && git commit -m "feat: ViewModel 新增 chartMode / historyVisibleCount / expandedDate 字段和方法"
```

---

### Task 4: 添加字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: 添加中文字符串**

在 `strings.xml` 中添加（在 `insights_` 相关字符串区域）：

```xml
<string name="insights_chart_balance">余额</string>
<string name="insights_chart_consumption">消耗</string>
<string name="insights_history_title">历史日汇总</string>
<string name="insights_history_load_more">加载更多 (%d天)</string>
<string name="insights_history_all_loaded">已加载全部</string>
<string name="insights_history_open">开盘余额</string>
<string name="insights_history_close">收盘余额</string>
<string name="insights_history_samples">采样次数</string>
<string name="insights_history_no_data">暂无数据</string>
```

- [ ] **Step 2: 添加英文字符串**

在 `strings.xml` (values-en) 中添加：

```xml
<string name="insights_chart_balance">Balance</string>
<string name="insights_chart_consumption">Consumed</string>
<string name="insights_history_title">Daily History</string>
<string name="insights_history_load_more">Load more (%d days)</string>
<string name="insights_history_all_loaded">All loaded</string>
<string name="insights_history_open">Open</string>
<string name="insights_history_close">Close</string>
<string name="insights_history_samples">Samples</string>
<string name="insights_history_no_data">No data</string>
```

- [ ] **Step 3: 提交**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml && git commit -m "feat: 添加消耗图表和历史日汇总字符串资源"
```

---

### Task 5: InsightsScreen — 消耗图表切换

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/InsightsScreen.kt`

**Interfaces:**
- Consumes: `uiState.chartMode` from Task 3, string resources from Task 4
- Produces: DailyCard 中图表上方有 余额|消耗 切换，图表根据 chartMode 选择数据源

- [ ] **Step 1: 修改 DailyCard 组件签名和实现**

修改 `DailyCard` 函数签名，添加 `chartMode` 和 `onChartModeChange` 参数，在图表上方添加切换行，图表绘制根据模式选择数据源。

具体改动：

**DailyCard 函数签名（第 377 行附近）：**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyCard(
    points: List<DailyPoint>,
    bill: DailyBillReport,
    estimate: DepletionEstimate?,
    currency: String,
    rangeDays: Int,
    insufficientData: Boolean = false,
    chartMode: String = "balance",                              // 新增
    onChartModeChange: (String) -> Unit = {},                   // 新增
    onRangeDaysChange: (Int) -> Unit
)
```

**在时间范围 FilterChip 行之后、图表之前插入图表模式切换行（第 432 行 `Spacer(modifier = Modifier.height(12.dp))` 之后，图表 `if (points.isEmpty())` 之前）：**

```kotlin
Spacer(modifier = Modifier.height(8.dp))

// 余额/消耗 图表模式切换
Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.verticalScroll(rememberScrollState())  // not needed, single row
) {
    listOf(
        "balance" to R.string.insights_chart_balance,
        "consumed" to R.string.insights_chart_consumption
    ).forEach { (mode, resId) ->
        FilterChip(
            selected = chartMode == mode,
            onClick = { onChartModeChange(mode) },
            label = {
                Text(
                    stringResource(resId),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            },
            modifier = Modifier.height(28.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}
```

**修改 DailyLineChart 调用，传递 chartMode，并在图表内部按模式选择数据源：**

给 `DailyLineChart` 添加 `chartMode` 参数：

```kotlin
DailyLineChart(
    data = points,
    chartMode = chartMode,   // 新增
    modifier = Modifier.fillMaxWidth().height(200.dp)
)
```

**修改 DailyLineChart 函数（第 952 行），添加 chartMode 参数并修改数据源：**

```kotlin
@Composable
private fun DailyLineChart(
    data: List<DailyPoint>,
    chartMode: String = "balance",   // 新增
    modifier: Modifier = Modifier
) {
    // ...

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val values = if (chartMode == "consumed") {
            data.map { it.consumed }
        } else {
            data.map { it.balance }
        }
        // ... 后续代码不变，values 已经根据 chartMode 设定
    }
}
```

**消耗模式下隐藏充值/赠送标记：**

在图表末尾的充值/赠送标记循环处（约第 1144-1173 行），添加条件：

```kotlin
// ── 充值日 ▲ 标记 ──
if (chartMode == "balance") {   // 仅在余额模式下显示
    for (i in data.indices) {
        if (data[i].toppedUp > 0f) {
            // ... existing triangle code ...
        }
    }

    // ── 赠送日 ◆ 标记 ──
    for (i in data.indices) {
        if (data[i].granted > 0f) {
            // ... existing diamond code ...
        }
    }
}
```

**更新调用处（InsightsScreen 函数约第 124 行），传递 chartMode：**

```kotlin
DailyCard(
    points = uiState.dailyOutput?.dailyPoints ?: emptyList(),
    bill = uiState.dailyOutput?.billReport
        ?: DailyBillReport(0f, 0f, 0f, 0f, ""),
    estimate = uiState.dailyOutput?.estimate,
    currency = uiState.selectedCurrency,
    rangeDays = uiState.rangeDays,
    insufficientData = uiState.dailyOutput?.insufficientData ?: true,
    chartMode = uiState.chartMode,                              // 新增
    onChartModeChange = { viewModel.setChartMode(it) },         // 新增
    onRangeDaysChange = { viewModel.setRangeDays(it) }
)
```

- [ ] **Step 2: 编译验证**

```bash
cd /c/Users/Administrator/DeepSeekBalance && export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10" && export ANDROID_HOME="$HOME/Android/Sdk" && ./gradlew.bat compileDebugKotlin --no-daemon
```

- [ ] **Step 3: 运行全部单元测试确认无回归**

```bash
./gradlew.bat testDebugUnitTest --no-daemon
```

- [ ] **Step 4: 提交**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git add app/src/main/java/com/balancesentinel/app/ui/screen/InsightsScreen.kt && git commit -m "feat: DailyCard 新增余额/消耗图表模式切换"
```

---

### Task 6: InsightsScreen — 历史日汇总卡片

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/InsightsScreen.kt`

**Interfaces:**
- Consumes: `uiState.historyVisibleCount`, `uiState.expandedDate`, `dailyOutput.dailyPoints` from Task 3, string resources from Task 4
- Produces: DailyHistoryCard 组件，列表展示每日数据，支持展开和分页

- [ ] **Step 1: 在 InsightsScreen 中添加 DailyHistoryCard 调用**

在 `InsightsScreen` 函数末尾（第 136 行 `Spacer(modifier = Modifier.height(8.dp))` 之前），DailyCard 之后插入：

```kotlin
// ── Card 3: 历史日汇总 ──
DailyHistoryCard(
    points = uiState.dailyOutput?.dailyPoints ?: emptyList(),
    currency = uiState.selectedCurrency,
    visibleCount = uiState.historyVisibleCount,
    expandedDate = uiState.expandedDate,
    onToggleExpand = { viewModel.toggleExpandDate(it) },
    onLoadMore = { viewModel.loadMoreHistory() }
)
```

- [ ] **Step 2: 实现 DailyHistoryCard 组件**

在文件末尾（第 1276 行之前）添加完整的 `DailyHistoryCard`：

```kotlin
// ═══════════════════════════════════════════════════════════
// Card 3: 历史日汇总
// ═══════════════════════════════════════════════════════════

@Composable
private fun DailyHistoryCard(
    points: List<DailyPoint>,
    currency: String,
    visibleCount: Int,
    expandedDate: String?,
    onToggleExpand: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    if (points.isEmpty()) return

    val reversed = points.reversed()  // 最新在前
    val visible = reversed.take(visibleCount)
    val hasMore = visibleCount < reversed.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.insights_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            visible.forEach { point ->
                val isExpanded = expandedDate == point.date
                val isGap = point.consumed == 0f && point.toppedUp == 0f && point.granted == 0f

                Column {
                    // 折叠行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isGap) Modifier
                                    .clickable { onToggleExpand(point.date) }
                                else Modifier
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = point.date,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isGap)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (point.consumed > 0f) {
                                Text(
                                    text = "-${FormatUtils.currencySymbol(currency)}%.2f".format(point.consumed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            } else if (point.toppedUp == 0f && point.granted == 0f) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                text = "${FormatUtils.currencySymbol(currency)}%.2f".format(point.balance),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isGap) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isExpanded) "▼" else "▶",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 展开区域
                    if (isExpanded) {
                        val netChange = point.toppedUp + point.granted - point.consumed
                        val netColor = when {
                            netChange > 0 -> WalletColors.success
                            netChange < 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val netPrefix = if (netChange >= 0) "+" else ""

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            HistoryDetailRow(stringResource(R.string.insights_label_consumed),
                                "-${FormatUtils.currencySymbol(currency)}%.2f".format(point.consumed),
                                MaterialTheme.colorScheme.error)
                            if (point.toppedUp > 0f) {
                                HistoryDetailRow(stringResource(R.string.insights_label_topped_up),
                                    "+${FormatUtils.currencySymbol(currency)}%.2f".format(point.toppedUp),
                                    WalletColors.success)
                            }
                            if (point.granted > 0f) {
                                HistoryDetailRow(stringResource(R.string.insights_label_granted),
                                    "+${FormatUtils.currencySymbol(currency)}%.2f".format(point.granted),
                                    WalletColors.granted)
                            }
                            HistoryDetailRow(stringResource(R.string.insights_label_net),
                                "$netPrefix${FormatUtils.currencySymbol(currency)}%.2f".format(netChange),
                                netColor)
                            HistoryDetailRow(stringResource(R.string.insights_history_open),
                                "${FormatUtils.currencySymbol(currency)}%.2f".format(point.open),
                                MaterialTheme.colorScheme.onSurfaceVariant)
                            HistoryDetailRow(stringResource(R.string.insights_history_close),
                                "${FormatUtils.currencySymbol(currency)}%.2f".format(point.balance),
                                MaterialTheme.colorScheme.onSurfaceVariant)
                            HistoryDetailRow(stringResource(R.string.insights_history_samples),
                                "${point.sampleCount}",
                                MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 分隔线（非最后一项）
                if (point != visible.last()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                }
            }

            // 加载更多按钮
            if (hasMore) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.TextButton(onClick = onLoadMore) {
                        Text(
                            text = stringResource(R.string.insights_history_load_more)
                                .format(visibleCount.coerceAtMost(points.size - visible.size)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (points.size > 7) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.insights_history_all_loaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}
```

- [ ] **Step 3: 添加缺失的 import**

在文件顶部 import 区域添加：

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
```

（如果之前没有用到的话，`clickable` 可能已在 scope 中）

- [ ] **Step 4: 编译验证**

```bash
cd /c/Users/Administrator/DeepSeekBalance && export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10" && export ANDROID_HOME="$HOME/Android/Sdk" && ./gradlew.bat compileDebugKotlin --no-daemon
```

- [ ] **Step 5: 运行全部单元测试确认无回归**

```bash
./gradlew.bat testDebugUnitTest --no-daemon
```

- [ ] **Step 6: 提交**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git add app/src/main/java/com/balancesentinel/app/ui/screen/InsightsScreen.kt && git commit -m "feat: 新增 DailyHistoryCard 历史日汇总卡片"
```

---

### Task 7: 最终验证与构建

- [ ] **Step 1: 运行全部单元测试**

```bash
cd /c/Users/Administrator/DeepSeekBalance && export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10" && export ANDROID_HOME="$HOME/Android/Sdk" && ./gradlew.bat testDebugUnitTest --no-daemon
```

预期: 全部通过，0 failures

- [ ] **Step 2: Debug 构建验证**

```bash
./gradlew.bat assembleDebug --no-daemon
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: 检查 git log 确认提交历史清晰**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git log --oneline -10
```

- [ ] **Step 4: 最终提交（如有未提交变更）**

```bash
cd /c/Users/Administrator/DeepSeekBalance && git status
```
