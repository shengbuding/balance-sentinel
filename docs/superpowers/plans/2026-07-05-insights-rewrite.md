# 洞察功能重写 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将洞察功能从旧 InsightEngine 统一窗口方案重写为 IntradayEngine（24h 滑动窗口）+ DailyEngine（长期日历天）双引擎架构。

**Architecture:** 两个纯 Kotlin 计算引擎各自独立，共享 RawRecordStore（保留 ≥24h）+ DailySummaryStore（自动补零）底层存储。CleanupScheduler 在午夜和启动时批量聚合删除。InsightsViewModel 编排双引擎输出到两个独立 UI 卡片。

**Tech Stack:** Kotlin + Jetpack Compose + Material 3, JUnit 4 + MockK + Robolectric, SharedPreferences + kotlinx.serialization

**Spec:** `docs/superpowers/specs/2026-07-05-insights-rewrite-design.md`

## Global Constraints

- RawRecord 上限 90,000 条，保留 ≥24 小时
- 充值检测: toppedUpBalance 累计差值 ≥1 且接近整数（小数 < 0.01）
- 历史日摘要一旦生成即冻结，永不修改
- 日期连续性：自动补零（sampleCount=0 标记）
- 删除仅在聚合后批量执行，不做实时判定
- 今日 DailySummary 每次刷新后实时覆盖
- 非今日 DailySummary 不容修改
- 项目构建命令: `export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10"; export ANDROID_HOME="$HOME/Android/Sdk"; ./gradlew.bat assembleDebug testDebugUnitTest --no-daemon`
- 所有 engine/aggregator 测试使用纯 JVM（无 Robolectric），存储测试使用 Robolectric

---

## File Map

### 新建文件

| 文件 | 职责 |
|------|------|
| `data/engine/IntradayModels.kt` | IntradayInput, IntradayOutput, IntradayPoint, IntradayBillReport |
| `data/engine/IntradayEngine.kt` | 24h 滑动窗口计算引擎（纯 Kotlin object） |
| `data/engine/DailyModels.kt` | DailyInput, DailyOutput, DailyPoint, DailyBillReport, DepletionEstimate |
| `data/engine/DailyEngine.kt` | 长期日历天计算引擎（纯 Kotlin object） |
| `data/repository/CleanupScheduler.kt` | 午夜+启动聚合/补零/删除调度器 |

### 改造文件

| 文件 | 变更摘要 |
|------|---------|
| `data/repository/RawRecordStore.kt` | 移除跨天清空，新增 getRecordsSince/getRecordsForDate/getOldUngroupedDates/removeByDate |
| `data/repository/DailySummaryStore.kt` | 新增 upsert/getSummariesInRange/hasSummaryForDate/ensureContinuity |
| `data/repository/MidnightReceiver.kt` | 聚合路径改为调用 CleanupScheduler |
| `ui/viewmodel/HomeViewModel.kt` | 聚合路径改为调用 CleanupScheduler |
| `ui/viewmodel/InsightsViewModel.kt` | 完全重写为双引擎编排层 |
| `ui/screen/InsightsScreen.kt` | 重写为 24h 卡片 + 长期卡片双视图 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `data/engine/InsightEngine.kt` | 被 IntradayEngine + DailyEngine 替代 |
| `data/engine/InsightModels.kt` | 模型拆分到 IntradayModels + DailyModels |
| 原 `InsightEngineTest.kt` | 替换为新引擎测试 |

### 测试文件

| 文件 | 类型 |
|------|------|
| `data/engine/IntradayEngineTest.kt` | 新建，纯 JVM |
| `data/engine/DailyEngineTest.kt` | 新建，纯 JVM |
| `data/engine/RecordAggregatorTest.kt` | 保留不变 |
| `data/repository/RawRecordStoreTest.kt` | 改造，Robolectric |
| `data/repository/DailySummaryStoreTest.kt` | 改造，Robolectric |
| `data/repository/CleanupSchedulerTest.kt` | 新建，Robolectric |
| `ui/viewmodel/InsightsViewModelTest.kt` | 改造，Robolectric |

---

### Task 1: 新建模型文件

**Files:**
- Create: `app/src/main/java/com/example/deepseekbalance/data/engine/IntradayModels.kt`
- Create: `app/src/main/java/com/example/deepseekbalance/data/engine/DailyModels.kt`

**Interfaces:**
- Produces: `IntradayInput`, `IntradayOutput`, `IntradayPoint`, `IntradayBillReport`
- Produces: `DailyInput`, `DailyOutput`, `DailyPoint`, `DailyBillReport`, `DepletionEstimate`

- [ ] **Step 1: 创建 IntradayModels.kt**

```kotlin
package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.RawRecord

/**
 * IntradayEngine 的输入 — 24h 滑动窗口。
 */
data class IntradayInput(
    val rawRecords: List<RawRecord>,
    val filterCurrency: String,
    val filterAccountId: String?
)

/**
 * IntradayEngine 的输出。
 */
data class IntradayOutput(
    val trendPoints: List<IntradayPoint>,
    val billReport: IntradayBillReport,
    val dataPointCount: Int
)

/**
 * 24h 趋势图上单个数据点。
 */
data class IntradayPoint(
    val timestamp: Long,
    val actualBalance: Float,
    val isTopUp: Boolean,
    val isGrant: Boolean,
    val topUpAmount: Float,
    val grantAmount: Float
)

/**
 * 24h 账单汇总。
 */
data class IntradayBillReport(
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val netChange: Float
)
```

- [ ] **Step 2: 创建 DailyModels.kt**

```kotlin
package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord

/**
 * DailyEngine 的输入 — 长期日历天视图。
 */
data class DailyInput(
    val summaries: List<DailySummary>,
    val todayRawRecords: List<RawRecord>,
    val filterCurrency: String,
    val filterAccountId: String?,
    val rangeDays: Int
)

/**
 * DailyEngine 的输出。
 */
data class DailyOutput(
    val dailyPoints: List<DailyPoint>,
    val billReport: DailyBillReport,
    val estimate: DepletionEstimate?,
    val periodLabel: String,
    val isEmpty: Boolean
)

/**
 * 长期趋势图单个数据点。
 */
data class DailyPoint(
    val date: String,
    val balance: Float,
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val isGapFill: Boolean
)

/**
 * 长期账单汇总。
 */
data class DailyBillReport(
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val netChange: Float,
    val periodLabel: String
)

/**
 * 消耗预估（基于 consumed 值线性回归）。
 * null = 数据不足或消耗趋近于零。
 */
data class DepletionEstimate(
    val dailyRate: Float,
    val daysRemaining: Float,
    val depletionDate: String,
    val methodLabel: String
)
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/example/deepseekbalance/data/engine/IntradayModels.kt
git add app/src/main/java/com/example/deepseekbalance/data/engine/DailyModels.kt
git commit -m "feat: add IntradayModels and DailyModels for dual-engine insights"
```

---

### Task 2: IntradayEngine TDD

**Files:**
- Create: `app/src/test/java/com/example/deepseekbalance/data/engine/IntradayEngineTest.kt`
- Create: `app/src/main/java/com/example/deepseekbalance/data/engine/IntradayEngine.kt`

**Interfaces:**
- Consumes: `IntradayInput`, `IntradayOutput`, `IntradayPoint`, `IntradayBillReport` (Task 1)
- Consumes: `RawRecord` (existing)
- Produces: `object IntradayEngine { fun compute(input: IntradayInput): IntradayOutput }`

- [ ] **Step 1: 创建 IntradayEngineTest — 空输入和单记录测试**

```kotlin
package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.RawRecord
import org.junit.Assert.*
import org.junit.Test

class IntradayEngineTest {

    @Test
    fun `empty records returns zero output`() {
        val input = IntradayInput(emptyList(), "CNY", null)
        val output = IntradayEngine.compute(input)
        assertEquals(0, output.dataPointCount)
        assertEquals(0f, output.billReport.consumed)
        assertEquals(0f, output.billReport.toppedUp)
        assertEquals(0f, output.billReport.netChange)
    }

    @Test
    fun `single record returns one point with no top-up or consumption`() {
        val now = System.currentTimeMillis()
        val record = RawRecord("acc1", now, "CNY", 100f, 10f, 90f)
        val input = IntradayInput(listOf(record), "CNY", null)
        val output = IntradayEngine.compute(input)

        assertEquals(1, output.dataPointCount)
        assertEquals(100f, output.trendPoints[0].actualBalance)
        assertFalse(output.trendPoints[0].isTopUp)
        assertEquals(0f, output.billReport.consumed)
        assertEquals(0f, output.billReport.toppedUp)
    }

    @Test
    fun `filters records older than 24 hours`() {
        val now = System.currentTimeMillis()
        val old = RawRecord("acc1", now - 25 * 3600_000L, "CNY", 100f, 0f, 100f)
        val recent = RawRecord("acc1", now - 1 * 3600_000L, "CNY", 90f, 0f, 90f)
        val input = IntradayInput(listOf(old, recent), "CNY", null)
        val output = IntradayEngine.compute(input)

        assertEquals(1, output.dataPointCount)
        assertEquals(90f, output.trendPoints[0].actualBalance)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10"
export ANDROID_HOME="$HOME/Android/Sdk"
cd /c/Users/Administrator/DeepSeekBalance
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.IntradayEngineTest" 2>&1 | tail -10
```
预期: 编译失败 — `IntradayEngine` 未定义

- [ ] **Step 3: 实现 IntradayEngine 最小版本（满足以上三个测试）**

```kotlin
package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.RawRecord

object IntradayEngine {

    fun compute(input: IntradayInput): IntradayOutput {
        val now = System.currentTimeMillis()
        val cutoff = now - 24 * 3600_000L

        val filtered = input.rawRecords
            .filter { it.currency == input.filterCurrency }
            .filter { input.filterAccountId == null || it.accountId == input.filterAccountId }
            .filter { it.timestamp >= cutoff }
            .sortedBy { it.timestamp }

        if (filtered.isEmpty()) {
            return IntradayOutput(emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0)
        }

        if (filtered.size == 1) {
            val r = filtered[0]
            return IntradayOutput(
                trendPoints = listOf(IntradayPoint(r.timestamp, r.totalBalance, false, false, 0f, 0f)),
                billReport = IntradayBillReport(0f, 0f, 0f, 0f),
                dataPointCount = 1
            )
        }

        val points = mutableListOf<IntradayPoint>()
        var totalConsumed = 0f
        var totalToppedUp = 0f
        var totalGranted = 0f

        points.add(IntradayPoint(filtered[0].timestamp, filtered[0].totalBalance, false, false, 0f, 0f))

        for (i in 1 until filtered.size) {
            val prev = filtered[i - 1]
            val curr = filtered[i]

            val balanceDelta = curr.totalBalance - prev.totalBalance
            val topUpDelta = curr.toppedUpBalance - prev.toppedUpBalance
            val grantDelta = curr.grantedBalance - prev.grantedBalance

            val isTopUp = topUpDelta >= 1f && isNearInteger(topUpDelta)
            val topUpAmount = if (isTopUp) topUpDelta else 0f
            val isGrant = grantDelta > 0f
            val grantAmount = if (isGrant) grantDelta else 0f

            val consumption = (topUpAmount + grantAmount - balanceDelta).coerceAtLeast(0f)

            totalConsumed += consumption
            totalToppedUp += topUpAmount
            totalGranted += grantAmount

            points.add(IntradayPoint(curr.timestamp, curr.totalBalance, isTopUp, isGrant, topUpAmount, grantAmount))
        }

        return IntradayOutput(
            trendPoints = points,
            billReport = IntradayBillReport(
                consumed = totalConsumed,
                toppedUp = totalToppedUp,
                granted = totalGranted,
                netChange = totalToppedUp + totalGranted - totalConsumed
            ),
            dataPointCount = points.size
        )
    }

    private fun isNearInteger(value: Float): Boolean {
        val frac = value - value.toLong().toFloat()
        return frac < 0.01f || frac > 0.99f
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.IntradayEngineTest" 2>&1 | tail -5
```
预期: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 5: 补充测试 — 正常消耗**

```kotlin
@Test
fun `normal consumption between two refreshes`() {
    val now = System.currentTimeMillis()
    val records = listOf(
        RawRecord("acc1", now - 3600_000L, "CNY", 100f, 0f, 100f),
        RawRecord("acc1", now, "CNY", 85f, 0f, 85f)
    )
    val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

    assertEquals(2, output.dataPointCount)
    assertEquals(85f, output.trendPoints[1].actualBalance)
    assertFalse(output.trendPoints[1].isTopUp)
    assertEquals(15f, output.billReport.consumed, 0.01f)
    assertEquals(0f, output.billReport.toppedUp, 0.01f)
    assertEquals(-15f, output.billReport.netChange, 0.01f)
}
```

- [ ] **Step 6: 运行测试验证通过**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.IntradayEngineTest" 2>&1 | tail -5
```
预期: BUILD SUCCESSFUL, 4 tests passed

- [ ] **Step 7: 补充测试 — 充值检测（整数 + 小数过滤）**

```kotlin
@Test
fun `top-up detection with exact integer delta`() {
    val now = System.currentTimeMillis()
    val records = listOf(
        RawRecord("acc1", now - 1800_000L, "CNY", 100f, 0f, 50f),
        RawRecord("acc1", now, "CNY", 130f, 0f, 80f)  // toppedUpBalance +30
    )
    val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

    assertTrue(output.trendPoints[1].isTopUp)
    assertEquals(30f, output.trendPoints[1].topUpAmount, 0.01f)
    assertEquals(30f, output.billReport.toppedUp, 0.01f)
    assertEquals(0f, output.billReport.consumed, 0.01f)  // no real consumption
    assertEquals(30f, output.billReport.netChange, 0.01f)
}

@Test
fun `top-up rejects non-integer delta`() {
    val now = System.currentTimeMillis()
    val records = listOf(
        RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
        RawRecord("acc1", now, "CNY", 110f, 0f, 60.5f)  // toppedUpBalance +10.5, not integer
    )
    val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

    assertFalse(output.trendPoints[1].isTopUp)
    assertEquals(0f, output.billReport.toppedUp, 0.01f)
}

@Test
fun `top-up rejects delta less than 1`() {
    val now = System.currentTimeMillis()
    val records = listOf(
        RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
        RawRecord("acc1", now, "CNY", 100.5f, 0f, 50.5f)  // toppedUpBalance +0.5
    )
    val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

    assertFalse(output.trendPoints[1].isTopUp)
}
```

- [ ] **Step 8: 运行测试验证通过**

预期: BUILD SUCCESSFUL, 7 tests passed

- [ ] **Step 9: 补充测试 — 混合场景（消耗+充值同一间隔）**

```kotlin
@Test
fun `mixed consumption and top-up in same interval`() {
    val now = System.currentTimeMillis()
    // 用户消耗了20，又充值了30，余额净增+10
    val records = listOf(
        RawRecord("acc1", now - 3600_000L, "CNY", 100f, 0f, 50f),
        RawRecord("acc1", now, "CNY", 110f, 0f, 80f)  // balance +10, toppedUp +30
    )
    val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

    assertTrue(output.trendPoints[1].isTopUp)
    assertEquals(30f, output.trendPoints[1].topUpAmount, 0.01f)
    assertEquals(20f, output.billReport.consumed, 0.01f)  // 30 - 10 = 20 real consumption
    assertEquals(30f, output.billReport.toppedUp, 0.01f)
    assertEquals(10f, output.billReport.netChange, 0.01f)
}

@Test
fun `grant detection`() {
    val now = System.currentTimeMillis()
    val records = listOf(
        RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
        RawRecord("acc1", now, "CNY", 110f, 10f, 50f)  // grantedBalance +10
    )
    val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

    assertTrue(output.trendPoints[1].isGrant)
    assertEquals(10f, output.trendPoints[1].grantAmount, 0.01f)
    assertEquals(10f, output.billReport.granted, 0.01f)
}

@Test
fun `currency filter applies correctly`() {
    val now = System.currentTimeMillis()
    val records = listOf(
        RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 100f),
        RawRecord("acc1", now, "USD", 50f, 0f, 50f)
    )
    val output = IntradayEngine.compute(IntradayInput(records, "USD", null))

    assertEquals(1, output.dataPointCount)
    assertEquals(50f, output.trendPoints[0].actualBalance)
}
```

- [ ] **Step 10: 运行全部测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.IntradayEngineTest" 2>&1 | tail -5
```
预期: BUILD SUCCESSFUL, 10 tests passed

- [ ] **Step 11: 提交**

```bash
git add app/src/test/java/com/example/deepseekbalance/data/engine/IntradayEngineTest.kt
git add app/src/main/java/com/example/deepseekbalance/data/engine/IntradayEngine.kt
git commit -m "feat: add IntradayEngine with 24h sliding window analysis"
```

---

### Task 3: DailyEngine TDD

**Files:**
- Create: `app/src/test/java/com/example/deepseekbalance/data/engine/DailyEngineTest.kt`
- Create: `app/src/main/java/com/example/deepseekbalance/data/engine/DailyEngine.kt`

**Interfaces:**
- Consumes: `DailyInput`, `DailyOutput`, `DailyPoint`, `DailyBillReport`, `DepletionEstimate` (Task 1)
- Consumes: `DailySummary`, `RawRecord` (existing)
- Consumes: `RecordAggregator.aggregate()` (existing, unchanged)
- Produces: `object DailyEngine { fun compute(input: DailyInput): DailyOutput }`

- [ ] **Step 1: 创建 DailyEngineTest — 基础测试**

```kotlin
package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import org.junit.Assert.*
import org.junit.Test

class DailyEngineTest {

    @Test
    fun `empty summaries returns empty output`() {
        val input = DailyInput(emptyList(), emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertTrue(output.isEmpty)
        assertEquals(0, output.dailyPoints.size)
        assertEquals("最近7天", output.periodLabel)
    }

    @Test
    fun `single day summary produces one daily point`() {
        val summary = DailySummary(
            accountId = "acc1", date = "2026-07-04", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 5,
            toppedUpBalanceClose = 50f, grantedBalanceClose = 10f
        )
        val input = DailyInput(listOf(summary), emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertEquals(1, output.dailyPoints.size)
        assertEquals("2026-07-04", output.dailyPoints[0].date)
        assertEquals(90f, output.dailyPoints[0].balance)
        assertEquals(10f, output.dailyPoints[0].consumed, 0.01f)
        assertFalse(output.dailyPoints[0].isGapFill)
        assertEquals(10f, output.billReport.consumed, 0.01f)
    }

    @Test
    fun `multiple days aggregate correctly`() {
        val summaries = listOf(
            DailySummary("acc1", "2026-07-01", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 3, 0f, 0f),
            DailySummary("acc1", "2026-07-02", "CNY", 90f, 75f, 15f, 0f, 0f, 82f, 2, 0f, 0f),
            DailySummary("acc1", "2026-07-03", "CNY", 75f, 70f, 5f, 30f, 0f, 72f, 4, 30f, 0f)
        )
        val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
        val output = DailyEngine.compute(input)

        assertEquals(3, output.dailyPoints.size)
        assertEquals(30f, output.billReport.consumed, 0.01f)   // 10 + 15 + 5
        assertEquals(30f, output.billReport.toppedUp, 0.01f)   // 30 from 7/3
        assertEquals(0f, output.billReport.netChange, 0.01f)   // 30 - 30 = 0
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.DailyEngineTest" 2>&1 | tail -5
```
预期: 编译失败

- [ ] **Step 3: 实现 DailyEngine**

```kotlin
package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object DailyEngine {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun compute(input: DailyInput): DailyOutput {
        val filtered = input.summaries
            .filter { it.currency == input.filterCurrency }
            .filter { input.filterAccountId == null || it.accountId == input.filterAccountId }
            .sortedBy { it.date }

        val window = filtered.takeLast(input.rangeDays)
        val periodLabel = buildPeriodLabel(input.rangeDays)

        if (window.isEmpty()) {
            return DailyOutput(emptyList(), DailyBillReport(0f, 0f, 0f, 0f, periodLabel), null, periodLabel, true)
        }

        // Today's real-time override
        val today = dateFormat.format(Date())
        val todaySummary = if (input.todayRawRecords.isNotEmpty()) {
            val todayFiltered = input.todayRawRecords.filter {
                it.currency == input.filterCurrency &&
                    (input.filterAccountId == null || it.accountId == input.filterAccountId)
            }
            if (todayFiltered.isNotEmpty()) {
                RecordAggregator.aggregate(todayFiltered, today).firstOrNull()
            } else null
        } else null

        val dailyPoints = window.map { summary ->
            if (summary.date == today && todaySummary != null) {
                DailyPoint(today, todaySummary.close, todaySummary.consumed,
                    todaySummary.toppedUp, todaySummary.granted, false)
            } else {
                DailyPoint(summary.date, summary.close, summary.consumed,
                    summary.toppedUp, summary.granted, summary.sampleCount == 0)
            }
        }

        val totalConsumed = dailyPoints.sumOf { it.consumed.toDouble() }.toFloat()
        val totalToppedUp = dailyPoints.sumOf { it.toppedUp.toDouble() }.toFloat()
        val totalGranted = dailyPoints.sumOf { it.granted.toDouble() }.toFloat()

        val estimate = computeDepletionEstimate(dailyPoints, input.rangeDays)

        return DailyOutput(
            dailyPoints = dailyPoints,
            billReport = DailyBillReport(totalConsumed, totalToppedUp, totalGranted,
                totalToppedUp + totalGranted - totalConsumed, periodLabel),
            estimate = estimate,
            periodLabel = periodLabel,
            isEmpty = false
        )
    }

    private fun computeDepletionEstimate(
        points: List<DailyPoint>,
        rangeDays: Int
    ): DepletionEstimate? {
        val withConsumption = points.filter { it.consumed > 0f }
        if (withConsumption.size < 3) return null

        val xValues = withConsumption.indices.map { it.toFloat() }
        val yValues = withConsumption.map { it.consumed }
        val n = withConsumption.size.toFloat()

        val sumX = xValues.sum()
        val sumY = yValues.sum()
        val sumXY = xValues.zip(yValues).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
        val sumX2 = xValues.sumOf { (it * it).toDouble() }.toFloat()

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0f) return null

        val slope = (n * sumXY - sumX * sumY) / denominator
        if (slope <= 0f) return null

        val lastBalance = points.lastOrNull()?.balance ?: return null
        val daysRemaining = lastBalance / slope

        val depletionDate = try {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, daysRemaining.roundToInt())
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
        } catch (_: Exception) { "—" }

        return DepletionEstimate(
            dailyRate = slope,
            daysRemaining = daysRemaining,
            depletionDate = depletionDate,
            methodLabel = "基于最近${rangeDays}天消耗数据线性回归"
        )
    }

    private fun buildPeriodLabel(days: Int): String = when (days) {
        7 -> "最近7天"; 14 -> "最近14天"; 30 -> "最近30天"
        90 -> "最近90天"; 365 -> "最近1年"; else -> "最近${days}天"
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.DailyEngineTest" 2>&1 | tail -5
```
预期: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 5: 补充测试 — 补零日、范围切换、消耗预估**

```kotlin
@Test
fun `gap-fill days are marked with isGapFill`() {
    val summaries = listOf(
        DailySummary("acc1", "2026-07-01", "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 0, 0f, 0f),
        DailySummary("acc1", "2026-07-04", "CNY", 100f, 80f, 20f, 0f, 0f, 90f, 5, 0f, 0f)
    )
    val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
    val output = DailyEngine.compute(input)

    assertEquals(2, output.dailyPoints.size)
    assertTrue(output.dailyPoints[0].isGapFill)   // sampleCount=0
    assertFalse(output.dailyPoints[1].isGapFill)   // sampleCount=5
}

@Test
fun `rangeDays limits window`() {
    val summaries = (1..20).map { day ->
        val date = "2026-06-${(day + 10).toString().padStart(2, '0')}"
        DailySummary("acc1", date, "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f)
    }
    val input7 = DailyInput(summaries, emptyList(), "CNY", null, 7)
    assertEquals(7, DailyEngine.compute(input7).dailyPoints.size)

    val input30 = DailyInput(summaries, emptyList(), "CNY", null, 30)
    assertEquals(20, DailyEngine.compute(input30).dailyPoints.size)  // only 20 exist
}

@Test
fun `depletion estimate with steady consumption`() {
    val summaries = (1..7).map { day ->
        val date = "2026-07-0$day"
        DailySummary("acc1", date, "CNY", 100f - day * 5f, 100f - (day - 1) * 5f,
            5f, 0f, 0f, 100f - (day - 0.5f) * 5f, 3, 0f, 0f)
    }
    val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
    val output = DailyEngine.compute(input)

    assertNotNull(output.estimate)
    assertEquals(5f, output.estimate!!.dailyRate, 0.5f)
}

@Test
fun `no estimate with insufficient data`() {
    val summaries = listOf(
        DailySummary("acc1", "2026-07-01", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 3, 0f, 0f)
    )
    val input = DailyInput(summaries, emptyList(), "CNY", null, 7)
    val output = DailyEngine.compute(input)

    assertNull(output.estimate)  // only 1 data point
}

@Test
fun `today raw records override today summary`() {
    val now = System.currentTimeMillis()
    val summary = DailySummary("acc1", "2026-07-05", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 1, 0f, 0f)
    val todayRecords = listOf(
        RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 100f),
        RawRecord("acc1", now, "CNY", 75f, 0f, 75f)
    )
    val input = DailyInput(listOf(summary), todayRecords, "CNY", null, 7)
    val output = DailyEngine.compute(input)

    assertEquals(25f, output.dailyPoints[0].consumed, 0.01f)  // from raw records, not summary
    assertEquals(75f, output.dailyPoints[0].balance)
}
```

- [ ] **Step 6: 运行全部测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.engine.DailyEngineTest" 2>&1 | tail -5
```
预期: BUILD SUCCESSFUL, 9 tests passed

- [ ] **Step 7: 提交**

```bash
git add app/src/test/java/com/example/deepseekbalance/data/engine/DailyEngineTest.kt
git add app/src/main/java/com/example/deepseekbalance/data/engine/DailyEngine.kt
git commit -m "feat: add DailyEngine with long-term calendar day analysis"
```

---

### Task 4: RawRecordStore 改造

**Files:**
- Modify: `app/src/main/java/com/example/deepseekbalance/data/repository/RawRecordStore.kt`
- Modify: `app/src/test/java/com/example/deepseekbalance/data/repository/RawRecordStoreTest.kt`

**Interfaces:**
- Consumes: `RawRecord` (existing, unchanged)
- Produces: `getRecordsSince(context, timestamp)`, `getRecordsForDate(context, date)`, `getDistinctDates(context)`, `removeByDate(context, date, minAgeMs)`
- Changed: `addRecord()` — 移除跨天自动清空逻辑
- Changed: `removeByDate()` — 新增方法，仅删除年龄 > minAgeMs 的记录

- [ ] **Step 1: 修改 RawRecordStore — 移除 addRecord 中的跨天清空逻辑**

修改 `addRecord()` 方法。删除日期变更检测段落（第 37-43 行）:

```kotlin
// 删除以下代码:
// 日期变更检测：如果已有记录非今日数据，自动清空
val firstRecordDate = records.firstOrNull()?.let {
    dateFormat.format(Date(it.timestamp))
}
if (firstRecordDate != null && firstRecordDate != today) {
    records.clear()
}
```

`addRecord()` 变为:

```kotlin
fun addRecord(context: Context, record: RawRecord) {
    try {
        val records = getRecordsInternal(context).toMutableList()
        records.add(record)

        // 上限兜底：保留最新 MAX_RECORDS 条
        if (records.size > MAX_RECORDS) {
            records.subList(0, records.size - MAX_RECORDS).clear()
        }

        val serialized = json.encodeToString(ListSerializer(RawRecord.serializer()), records)
        getPrefs(context).edit().putString(KEY_RECORDS, serialized).apply()
    } catch (_: Exception) {
        // 记录写入失败不应影响刷新主流程
    }
}
```

- [ ] **Step 2: 添加新方法到 RawRecordStore**

```kotlin
/**
 * 读取指定时间戳之后的所有记录（24h 滑动窗口）。
 */
fun getRecordsSince(context: Context, timestamp: Long): List<RawRecord> {
    return try {
        getRecordsInternal(context).filter { it.timestamp >= timestamp }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 读取指定日期的所有记录。
 */
fun getRecordsForDate(context: Context, date: String): List<RawRecord> {
    return try {
        getRecordsInternal(context).filter {
            dateFormat.format(Date(it.timestamp)) == date
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 返回存储中所有不同的日期（yyyy-MM-dd）。
 */
fun getDistinctDates(context: Context): List<String> {
    return try {
        getRecordsInternal(context)
            .map { dateFormat.format(Date(it.timestamp)) }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 批量删除指定日期中年龄超过 minAgeMs 的记录。
 * 只删除旧记录，保留 < minAgeMs 的记录以维持 24h 滑动窗口完整性。
 */
fun removeByDate(context: Context, date: String, minAgeMs: Long = 24 * 3600_000L) {
    try {
        val now = System.currentTimeMillis()
        val remaining = getRecordsInternal(context).filter {
            dateFormat.format(Date(it.timestamp)) != date ||
                (now - it.timestamp) < minAgeMs
        }
        val originalSize = getRecordsInternal(context).size
        if (remaining.size < originalSize) {
            val serialized = json.encodeToString(ListSerializer(RawRecord.serializer()), remaining)
            getPrefs(context).edit().putString(KEY_RECORDS, serialized).apply()
        }
    } catch (_: Exception) {}
}
```

- [ ] **Step 3: 修改 RawRecordStoreTest — 移除跨天清空的旧测试，添加新测试**

删除旧测试中依赖跨天清空行为的测试。保留现有 12 tests 中不依赖跨天清空的测试。在文件末尾添加新测试:

```kotlin
@Test
fun `getRecordsSince filters by timestamp`() {
    val t0 = System.currentTimeMillis()
    RawRecordStore.addRecord(context, RawRecord("acc1", t0 - 2 * 3600_000L, "CNY", 100f, 0f, 100f))
    RawRecordStore.addRecord(context, RawRecord("acc1", t0 - 3600_000L, "CNY", 90f, 0f, 90f))
    RawRecordStore.addRecord(context, RawRecord("acc1", t0, "CNY", 80f, 0f, 80f))

    // 查询最近 90 分钟的
    val recent = RawRecordStore.getRecordsSince(context, t0 - 90 * 60_000L)
    assertEquals(1, recent.size)
    assertEquals(80f, recent[0].totalBalance)
}

@Test
fun `getRecordsForDate groups by calendar date`() {
    // 用固定时间戳构造昨日和今日记录
    val yesterdayMidnight = System.currentTimeMillis() - 24 * 3600_000L
    RawRecordStore.addRecord(context, RawRecord("acc1", yesterdayMidnight, "CNY", 100f, 0f, 100f))
    RawRecordStore.addRecord(context, RawRecord("acc1", yesterdayMidnight + 3600_000L, "CNY", 90f, 0f, 90f))

    val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(yesterdayMidnight))
    val dateRecords = RawRecordStore.getRecordsForDate(context, yesterday)
    assertEquals(2, dateRecords.size)
}

@Test
fun `getDistinctDates returns unique dates`() {
    val now = System.currentTimeMillis()
    val day1 = now - 3 * 24 * 3600_000L
    val day2 = now - 24 * 3600_000L

    RawRecordStore.addRecord(context, RawRecord("acc1", day1, "CNY", 100f, 0f, 100f))
    RawRecordStore.addRecord(context, RawRecord("acc1", day1 + 1000L, "CNY", 90f, 0f, 90f))
    RawRecordStore.addRecord(context, RawRecord("acc1", day2, "CNY", 80f, 0f, 80f))

    val dates = RawRecordStore.getDistinctDates(context)
    assertTrue(dates.size >= 2)
}

@Test
fun `removeByDate deletes only old records of specified date`() {
    val now = System.currentTimeMillis()
    val oldTimestamp = now - 25 * 3600_000L  // 25h ago
    val recentTimestamp = now - 1 * 3600_000L // 1h ago
    val oldDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(oldTimestamp))

    RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp, "CNY", 100f, 0f, 100f))
    RawRecordStore.addRecord(context, RawRecord("acc1", recentTimestamp, "CNY", 80f, 0f, 80f))

    RawRecordStore.removeByDate(context, oldDate)  // default minAgeMs = 24h

    // 旧记录被删除
    val oldRecords = RawRecordStore.getRecordsForDate(context, oldDate)
    assertEquals(0, oldRecords.size)

    // 新记录保留
    val allRecords = RawRecordStore.getAllRecords(context)
    assertEquals(1, allRecords.size)
    assertEquals(80f, allRecords[0].totalBalance)
}

@Test
fun `removeByDate keeps records younger than minAgeMs`() {
    val now = System.currentTimeMillis()
    val recentTimestamp = now - 1 * 3600_000L  // 1h ago
    val recentDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(recentTimestamp))

    RawRecordStore.addRecord(context, RawRecord("acc1", recentTimestamp, "CNY", 100f, 0f, 100f))

    RawRecordStore.removeByDate(context, recentDate)  // default minAgeMs = 24h

    // 不到 24h 的记录被保留
    val records = RawRecordStore.getAllRecords(context)
    assertEquals(1, records.size)
}

@Test
fun `records no longer auto-clear on date change`() {
    val oldTimestamp = System.currentTimeMillis() - 25 * 3600_000L  // yesterday
    RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp, "CNY", 100f, 0f, 100f))

    val todayTimestamp = System.currentTimeMillis()
    RawRecordStore.addRecord(context, RawRecord("acc1", todayTimestamp, "CNY", 90f, 0f, 90f))

    // Both yesterday's and today's records persist
    val all = RawRecordStore.getAllRecords(context)
    assertEquals(2, all.size)
}
```

- [ ] **Step 4: 运行测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.repository.RawRecordStoreTest" 2>&1 | tail -10
```
预期: 所有测试通过（新+旧）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/deepseekbalance/data/repository/RawRecordStore.kt
git add app/src/test/java/com/example/deepseekbalance/data/repository/RawRecordStoreTest.kt
git commit -m "feat: RawRecordStore keeps records >=24h, add date-based query and batch delete"
```

---

### Task 5: DailySummaryStore 改造

**Files:**
- Modify: `app/src/main/java/com/example/deepseekbalance/data/repository/DailySummaryStore.kt`
- Modify: `app/src/test/java/com/example/deepseekbalance/data/repository/DailySummaryStoreTest.kt`

**Interfaces:**
- Consumes: `DailySummary` (existing, unchanged)
- Produces: `upsert(context, summary)`, `getSummariesInRange(context, from, to)`, `hasSummaryForDate(context, date, currency, accountId)`, `ensureContinuity(context, fromDate, toDate)`

- [ ] **Step 1: 新增 upsert 和查询方法**

在 DailySummaryStore 中添加:

```kotlin
/**
 * 添加或覆盖日摘要（date+currency+accountId 唯一）。
 * 用于今日数据实时覆写。
 */
fun upsert(context: Context, summary: DailySummary) {
    try {
        val summaries = getSummaries(context).toMutableList()
        val existingIndex = summaries.indexOfFirst {
            it.date == summary.date && it.currency == summary.currency && it.accountId == summary.accountId
        }
        if (existingIndex >= 0) {
            summaries[existingIndex] = summary
        } else {
            summaries.add(summary)
        }
        summaries.sortBy { it.date }
        val serialized = json.encodeToString(ListSerializer(DailySummary.serializer()), summaries)
        getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).apply()
    } catch (_: Exception) {}
}

/**
 * 按日期范围查询日摘要（含两端）。
 */
fun getSummariesInRange(context: Context, from: String, to: String): List<DailySummary> {
    return getSummaries(context).filter { it.date >= from && it.date <= to }
}

/**
 * 判断指定日期+币种+账户是否已有日摘要。
 */
fun hasSummaryForDate(context: Context, date: String, currency: String, accountId: String): Boolean {
    return getSummaries(context).any {
        it.date == date && it.currency == currency && it.accountId == accountId
    }
}
```

- [ ] **Step 2: 新增 ensureContinuity**

```kotlin
/**
 * 确保日期连续性。从 fromDate 到 toDate（不含今日）之间缺失的日期自动补零。
 *
 * 前置条件: fromDate 对应的条目必须在 DailySummaryStore 中存在。
 * DailySummaryStore 为空时不应调用此方法。
 *
 * 补零日特征:
 *   open/close = 前一个有效日的收盘值
 *   consumed/toppedUp/granted = 0
 *   sampleCount = 0（标记为无数据日）
 */
fun ensureContinuity(context: Context, fromDate: String, toDate: String) {
    try {
        val summaries = getSummaries(context).toMutableList()
        val fromSummary = summaries.find { it.date == fromDate } ?: return
        var carryBalance = fromSummary.close

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val cal = java.util.Calendar.getInstance()
        dateFormat.parse(fromDate)?.let { cal.time = it }
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)

        val toCal = java.util.Calendar.getInstance()
        dateFormat.parse(toDate)?.let { toCal.time = it }

        while (!cal.after(toCal)) {
            val date = dateFormat.format(cal.time)
            if (date >= today) break  // 不补今日

            val exists = summaries.any {
                it.date == date
            }
            if (!exists) {
                // 自补零需要为每个币种+账户组合补
                val currencies = fromSummary.currency
                val accountId = fromSummary.accountId
                summaries.add(DailySummary(
                    accountId = accountId,
                    date = date,
                    currency = currencies,
                    open = carryBalance,
                    close = carryBalance,
                    consumed = 0f,
                    toppedUp = 0f,
                    granted = 0f,
                    avgBalance = carryBalance,
                    sampleCount = 0,
                    toppedUpBalanceClose = 0f,
                    grantedBalanceClose = 0f
                ))
            } else {
                // 更新 carryBalance 为该日的 close
                summaries.find { it.date == date }?.let { carryBalance = it.close }
            }
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }

        summaries.sortBy { it.date }
        val serialized = json.encodeToString(ListSerializer(DailySummary.serializer()), summaries)
        getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).apply()
    } catch (_: Exception) {}
}
```

- [ ] **Step 3: 修改 DailySummaryStoreTest — 添加测试**

在 DailySummaryStoreTest 末尾追加:

```kotlin
@Test
fun `upsert inserts new summary`() {
    val summary = DailySummary("acc1", "2026-07-04", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 5, 0f, 0f)
    DailySummaryStore.upsert(context, summary)

    val all = DailySummaryStore.getSummaries(context)
    assertEquals(1, all.size)
    assertEquals("2026-07-04", all[0].date)
}

@Test
fun `upsert overwrites existing date+currency+account`() {
    val s1 = DailySummary("acc1", "2026-07-04", "CNY", 100f, 90f, 10f, 0f, 0f, 95f, 5, 0f, 0f)
    DailySummaryStore.upsert(context, s1)

    val s2 = DailySummary("acc1", "2026-07-04", "CNY", 100f, 80f, 20f, 0f, 0f, 90f, 8, 0f, 0f)
    DailySummaryStore.upsert(context, s2)

    val all = DailySummaryStore.getSummaries(context)
    assertEquals(1, all.size)  // still 1 entry
    assertEquals(20f, all[0].consumed, 0.01f)  // updated value
    assertEquals(8, all[0].sampleCount)  // updated count
}

@Test
fun `getSummariesInRange filters correctly`() {
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-01", "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f))
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-03", "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f))
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-05", "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f))

    val range = DailySummaryStore.getSummariesInRange(context, "2026-07-02", "2026-07-04")
    assertEquals(1, range.size)
    assertEquals("2026-07-03", range[0].date)
}

@Test
fun `hasSummaryForDate returns true only when summary exists`() {
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-04", "CNY", 100f, 100f, 0f, 0f, 0f, 100f, 1, 0f, 0f))

    assertTrue(DailySummaryStore.hasSummaryForDate(context, "2026-07-04", "CNY", "acc1"))
    assertFalse(DailySummaryStore.hasSummaryForDate(context, "2026-07-05", "CNY", "acc1"))
}

@Test
fun `ensureContinuity fills gaps between two summaries`() {
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-01", "CNY", 100f, 95f, 5f, 0f, 0f, 97f, 3, 0f, 0f))
    // Gap: 7/2, 7/3 missing
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-04", "CNY", 95f, 85f, 10f, 0f, 0f, 90f, 4, 0f, 0f))

    DailySummaryStore.ensureContinuity(context, "2026-07-01", "2026-07-03")

    val all = DailySummaryStore.getSummaries(context).sortedBy { it.date }
    assertTrue(all.any { it.date == "2026-07-02" && it.sampleCount == 0 })
    assertTrue(all.any { it.date == "2026-07-03" && it.sampleCount == 0 })
    assertEquals(95f, all.find { it.date == "2026-07-02" }!!.close)  // carryBalance
}

@Test
fun `ensureContinuity does not fill today`() {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    DailySummaryStore.upsert(context, DailySummary("acc1", "2026-07-01", "CNY", 100f, 95f, 5f, 0f, 0f, 97f, 3, 0f, 0f))

    DailySummaryStore.ensureContinuity(context, "2026-07-01", today)

    val all = DailySummaryStore.getSummaries(context)
    assertFalse(all.any { it.date == today && it.sampleCount == 0 })
}
```

- [ ] **Step 4: 运行测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.repository.DailySummaryStoreTest" 2>&1 | tail -10
```
预期: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/deepseekbalance/data/repository/DailySummaryStore.kt
git add app/src/test/java/com/example/deepseekbalance/data/repository/DailySummaryStoreTest.kt
git commit -m "feat: DailySummaryStore adds upsert, range query, ensureContinuity"
```

---

### Task 6: CleanupScheduler TDD

**Files:**
- Create: `app/src/main/java/com/example/deepseekbalance/data/repository/CleanupScheduler.kt`
- Create: `app/src/test/java/com/example/deepseekbalance/data/repository/CleanupSchedulerTest.kt`
- Modify: `app/src/main/java/com/example/deepseekbalance/receiver/MidnightReceiver.kt`
- Modify: `app/src/main/java/com/example/deepseekbalance/ui/viewmodel/HomeViewModel.kt`

**Interfaces:**
- Consumes: `RawRecordStore` (Task 4), `DailySummaryStore` (Task 5), `RecordAggregator` (existing)
- Produces: `object CleanupScheduler { suspend fun runCleanup(context: Context) }`

- [ ] **Step 1: 创建 CleanupSchedulerTest**

```kotlin
package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CleanupSchedulerTest {

    private lateinit var context: Context
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    @After
    fun tearDown() {
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    @Test
    fun `runCleanup aggregates old records and creates summaries`() = runTest {
        val oldTimestamp = System.currentTimeMillis() - 25 * 3600_000L  // 25h ago
        val yesterday = dateFormat.format(Date(oldTimestamp))
        RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp + 1000L, "CNY", 90f, 0f, 90f))

        CleanupScheduler.runCleanup(context)

        assertTrue(DailySummaryStore.hasSummaryForDate(context, yesterday, "CNY", "acc1"))
        // Records >24h deleted
        val remaining = RawRecordStore.getRecordsForDate(context, yesterday)
        assertEquals(0, remaining.size)
    }

    @Test
    fun `runCleanup preserves records younger than 24h`() = runTest {
        val recentTimestamp = System.currentTimeMillis() - 1 * 3600_000L  // 1h ago
        val today = dateFormat.format(Date())
        RawRecordStore.addRecord(context, RawRecord("acc1", recentTimestamp, "CNY", 100f, 0f, 100f))

        CleanupScheduler.runCleanup(context)

        val remaining = RawRecordStore.getAllRecords(context)
        assertEquals(1, remaining.size)  // 不到24h，保留
        assertFalse(DailySummaryStore.hasSummaryForDate(context, today, "CNY", "acc1"))  // 今日不汇总
    }

    @Test
    fun `runCleanup fills date gaps`() = runTest {
        val t3 = System.currentTimeMillis() - 3 * 24 * 3600_000L  // 3 days ago
        val day1 = dateFormat.format(Date(t3))
        val day3 = dateFormat.format(Date(System.currentTimeMillis() - 24 * 3600_000L))  // yesterday

        // Only day-3 has records
        RawRecordStore.addRecord(context, RawRecord("acc1", t3, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", t3 + 1000L, "CNY", 90f, 0f, 90f))

        CleanupScheduler.runCleanup(context)

        // Gap days should be filled
        val all = DailySummaryStore.getSummaries(context).sortedBy { it.date }
        assertTrue(all.any { it.date == day1 && it.sampleCount > 0 })   // real data
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" 2>&1 | tail -5
```
预期: 编译失败 — CleanupScheduler 未定义

- [ ] **Step 3: 实现 CleanupScheduler**

```kotlin
package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.engine.RecordAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 清理调度器：聚合旧原始记录 → 日摘要 → 补零 → 删除。
 * 在午夜闹钟和 App 启动时调用，两者执行相同逻辑互为冗余。
 */
object CleanupScheduler {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 执行一轮完整清理：
     * 1. 扫描所有 >24h 且未汇总的日期
     * 2. 聚合 → 写入 DailySummaryStore
     * 3. 补零间隙
     * 4. 删除已聚合的旧记录
     */
    suspend fun runCleanup(context: Context) = withContext(Dispatchers.IO) {
        try {
            val today = dateFormat.format(Date())

            // Step 1: 找到所有需要聚合的日期
            val allDates = RawRecordStore.getDistinctDates(context)
                .filter { it != today }  // 不处理今日

            val unaggregatedDates = allDates.filter { date ->
                // 检查该日期是否有记录年龄 >24h
                val records = RawRecordStore.getRecordsForDate(context, date)
                val now = System.currentTimeMillis()
                records.any { now - it.timestamp > 24 * 3600_000L }
            }

            if (unaggregatedDates.isEmpty()) return@withContext

            // Step 2: 逐日聚合
            for (date in unaggregatedDates.sorted()) {
                val records = RawRecordStore.getRecordsForDate(context, date)
                if (records.isEmpty()) continue

                val summaries = RecordAggregator.aggregate(records, date)
                for (summary in summaries) {
                    DailySummaryStore.upsert(context, summary)
                }
            }

            // Step 3: 补零 — 从最早日期到昨天
            if (unaggregatedDates.isNotEmpty()) {
                val allSummaries = DailySummaryStore.getSummaries(context).sortedBy { it.date }
                val lastDate = allSummaries.lastOrNull()?.date ?: return@withContext

                // Find earliest date that has a summary
                val earliestDate = allSummaries.firstOrNull()?.date ?: return@withContext
                val yesterday = dateFormat.format(Date(System.currentTimeMillis() - 24 * 3600_000L))

                DailySummaryStore.ensureContinuity(context, earliestDate, yesterday)
            }

            // Step 4: 删除已聚合日期的旧记录
            for (date in unaggregatedDates) {
                RawRecordStore.removeByDate(context, date)
            }
        } catch (_: Exception) {
            // 清理失败不应影响 App 正常运行
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
./gradlew.bat testDebugUnitTest --no-daemon --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" 2>&1 | tail -5
```
预期: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 5: 修改 MidnightReceiver — 使用 CleanupScheduler**

替换 `onReceive` 中的聚合逻辑 (第 25-36 行):

```kotlin
// 旧代码 (删除):
val rawRecords = RawRecordStore.getAllRecords(context)
if (rawRecords.isNotEmpty()) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val oldRecords = rawRecords.filter {
        today != SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.timestamp))
    }
    if (oldRecords.isNotEmpty()) {
        DailySummaryStore.aggregateAndSave(context, oldRecords)
        RawRecordStore.removeRecords(context, oldRecords)
        Log.i("MidnightReceiver", "Aggregated ${oldRecords.size} old records, ${rawRecords.size - oldRecords.size} today records preserved")
    }
}

// 新代码 (替换为):
kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
    CleanupScheduler.runCleanup(context)
    Log.i("MidnightReceiver", "Cleanup completed")
}
```

同时移除不再需要的 import: `DailySummaryStore`, `SimpleDateFormat`, `Date`, `Locale`（如无其他引用）。
添加 import: `com.balancesentinel.app.data.repository.CleanupScheduler`, `kotlinx.coroutines.*`

- [ ] **Step 6: 修改 HomeViewModel — 聚合路径使用 CleanupScheduler**

在 `HomeViewModel.kt` 中找到 `scheduleMidnightAndCheckSummary` 方法 (约第 130-144 行)，替换聚合逻辑:

```kotlin
// 旧代码 (删除):
private fun scheduleMidnightAndCheckSummary() {
    try {
        MidnightScheduler.schedule(getApplication())
        val allRecords = RawRecordStore.getAllRecords(getApplication())
        if (allRecords.isEmpty()) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val oldRecords = allRecords.filter {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.timestamp)) != today
        }
        if (oldRecords.isNotEmpty()) {
            DailySummaryStore.aggregateAndSave(getApplication(), oldRecords)
            RawRecordStore.removeRecords(getApplication(), oldRecords)
        }
    } catch (_: Exception) {}
}

// 新代码 (替换为):
private fun scheduleMidnightAndCheckSummary() {
    try {
        MidnightScheduler.schedule(getApplication())
        viewModelScope.launch {
            CleanupScheduler.runCleanup(getApplication())
        }
    } catch (_: Exception) {}
}
```

移除不再需要的 import: `SimpleDateFormat`, `Date`（如无其他引用）。
添加 import: `com.balancesentinel.app.data.repository.CleanupScheduler`

- [ ] **Step 7: 运行全部测试确认无回归**

```bash
./gradlew.bat testDebugUnitTest --no-daemon 2>&1 | tail -10
```
预期: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/example/deepseekbalance/data/repository/CleanupScheduler.kt
git add app/src/test/java/com/example/deepseekbalance/data/repository/CleanupSchedulerTest.kt
git add app/src/main/java/com/example/deepseekbalance/receiver/MidnightReceiver.kt
git add app/src/main/java/com/example/deepseekbalance/ui/viewmodel/HomeViewModel.kt
git commit -m "feat: add CleanupScheduler, wire into MidnightReceiver and HomeViewModel"
```

---

### Task 7: InsightsViewModel + InsightsScreen 重写

**Files:**
- Modify: `app/src/main/java/com/example/deepseekbalance/ui/viewmodel/InsightsViewModel.kt`
- Modify: `app/src/main/java/com/example/deepseekbalance/ui/screen/InsightsScreen.kt`
- Modify: `app/src/test/java/com/example/deepseekbalance/ui/viewmodel/InsightsViewModelTest.kt`

**Interfaces:**
- Consumes: `IntradayEngine.compute()` (Task 2), `DailyEngine.compute()` (Task 3)
- Consumes: `RawRecordStore`, `DailySummaryStore` (Task 4, 5)
- Produces: `InsightsUiState` (改造), `InsightsViewModel` (改造)

- [ ] **Step 1: 重写 InsightsUiState 和 InsightsViewModel**

替换整个 InsightsViewModel.kt:

```kotlin
package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.engine.DailyBillReport
import com.balancesentinel.app.data.engine.DailyEngine
import com.balancesentinel.app.data.engine.DailyInput
import com.balancesentinel.app.data.engine.DailyOutput
import com.balancesentinel.app.data.engine.DailyPoint
import com.balancesentinel.app.data.engine.DepletionEstimate
import com.balancesentinel.app.data.engine.IntradayBillReport
import com.balancesentinel.app.data.engine.IntradayEngine
import com.balancesentinel.app.data.engine.IntradayInput
import com.balancesentinel.app.data.engine.IntradayOutput
import com.balancesentinel.app.data.engine.IntradayPoint
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InsightsUiState(
    val isLoading: Boolean = false,
    val accounts: List<AccountInfo> = emptyList(),
    val selectedAccountId: String? = null,
    val availableCurrencies: List<String> = emptyList(),
    val selectedCurrency: String = "",
    val rangeDays: Int = 7,

    // 24h 引擎输出
    val intradayOutput: IntradayOutput? = null,
    // 长期引擎输出
    val dailyOutput: DailyOutput? = null
) {
    val intradayPoints: List<IntradayPoint>
        get() = intradayOutput?.trendPoints ?: emptyList()
    val intradayBill: IntradayBillReport
        get() = intradayOutput?.billReport ?: IntradayBillReport(0f, 0f, 0f, 0f)
    val dailyPoints: List<DailyPoint>
        get() = dailyOutput?.dailyPoints ?: emptyList()
    val dailyBill: DailyBillReport
        get() = dailyOutput?.billReport ?: DailyBillReport(0f, 0f, 0f, 0f, "")
    val estimate: DepletionEstimate?
        get() = dailyOutput?.estimate
    val isEmpty: Boolean
        get() = (intradayOutput?.dataPointCount ?: 0) == 0 && (dailyOutput?.isEmpty ?: true)
}

class InsightsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private val apiKeyManager = ApiKeyManager(application)

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val summaries = DailySummaryStore.getSummaries(getApplication())
                val allRaw = RawRecordStore.getAllRecords(getApplication())
                val currencies = (summaries.map { it.currency } + allRaw.map { it.currency }).distinct()

                apiKeyManager.migrateLegacyKeyIfNeeded()
                val accounts = apiKeyManager.getAccounts()

                val currency = _uiState.value.selectedCurrency.let {
                    if (it.isNotEmpty() && currencies.contains(it)) it
                    else currencies.firstOrNull() ?: ""
                }
                val accountId = _uiState.value.selectedAccountId
                val rangeDays = _uiState.value.rangeDays

                // Intraday: 24h sliding window
                val cutoff = System.currentTimeMillis() - 24 * 3600_000L
                val recentRaw = RawRecordStore.getRecordsSince(getApplication(), cutoff)
                val intradayInput = IntradayInput(recentRaw, currency, accountId)
                val intradayOutput = IntradayEngine.compute(intradayInput)

                // Daily: long-term calendar
                val todayRaw = RawRecordStore.getTodayRecords(getApplication())
                val dailyInput = DailyInput(summaries, todayRaw, currency, accountId, rangeDays)
                val dailyOutput = DailyEngine.compute(dailyInput)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    accounts = accounts,
                    availableCurrencies = currencies,
                    selectedCurrency = currency,
                    intradayOutput = intradayOutput,
                    dailyOutput = dailyOutput
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun selectCurrency(currency: String) {
        _uiState.value = _uiState.value.copy(selectedCurrency = currency)
        loadData()
    }

    fun selectAccount(accountId: String?) {
        _uiState.value = _uiState.value.copy(selectedAccountId = accountId)
        loadData()
    }

    fun setRangeDays(days: Int) {
        if (_uiState.value.rangeDays == days) return
        _uiState.value = _uiState.value.copy(rangeDays = days)
        loadData()
    }
}
```

- [ ] **Step 2: 重写 InsightsScreen — 双卡片布局**

主 screen composable 结构:

```kotlin
@Composable
fun InsightsScreen(viewModel: InsightsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isLoading) {
        // CircularProgressIndicator 居中
    } else if (uiState.availableCurrencies.isEmpty()) {
        // 空状态提示
    } else {
        Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 币种 Tab（仅多币种时显示）
            if (uiState.availableCurrencies.size > 1) { CurrencyTabRow(...) }
            // 账户筛选
            if (uiState.accounts.isNotEmpty()) { AccountFilterRow(...) }

            // ── 卡片 1: 24h 视图 ──
            IntradayCard(
                points = uiState.intradayPoints,
                bill = uiState.intradayBill,
                currency = uiState.selectedCurrency
            )

            // ── 卡片 2: 长期视图 ──
            DailyCard(
                points = uiState.dailyPoints,
                bill = uiState.dailyBill,
                estimate = uiState.estimate,
                currency = uiState.selectedCurrency,
                rangeDays = uiState.rangeDays,
                onRangeDaysChange = { viewModel.setRangeDays(it) }
            )
        }
    }
}
```

两个图表 composable 的签名（内部 Canvas 绘制复用现有 `BalanceLineChart` 模式）:

```kotlin
@Composable
private fun IntradayLineChart(
    data: List<IntradayPoint>,
    modifier: Modifier = Modifier
)
// X 轴: 智能抽稀 HH:mm，充值点 ▲ 绿色，赠送点 ◆ 紫色

@Composable
private fun DailyLineChart(
    data: List<DailyPoint>,
    modifier: Modifier = Modifier
)
// X 轴: 均匀 MM-DD，补零日虚点，充值日 ▲ 绿色
```

24h 账单卡片结构:

```kotlin
@Composable
private fun IntradayCard(
    points: List<IntradayPoint>,
    bill: IntradayBillReport,
    currency: String
) {
    Card(...) {
        Column {
            Text("24小时") // 标题
            if (points.isEmpty()) Text("数据不足")
            else {
                IntradayLineChart(points, Modifier.fillMaxWidth().height(200.dp))
                // 图例: 余额线, ▲ 充值, ◆ 赠送
                // 账单行: 消耗 | 充值 | 赠送 | 净变化
                if (bill.consumed > 0f) LabeledLine("消耗", "-¥${bill.consumed}", Red)
                if (bill.toppedUp > 0f) LabeledLine("充值", "+¥${bill.toppedUp}", Green)
                if (bill.granted > 0f) LabeledLine("赠送", "+¥${bill.granted}", Purple)
                // 净变化
                LabeledLine("净变化", formatNetChange(bill.netChange, currency), netColor)
            }
        }
    }
}
```

长期卡片类似，额外包含 FilterChip (7/14/30/90/365天) 和消耗预估行。

- [ ] **Step 3: 更新 InsightsViewModelTest**

重写测试以适配新的 API:

```kotlin
@Test
fun `loadData populates both intraday and daily outputs`() {
    // 添加一些测试数据
    val now = System.currentTimeMillis()
    RawRecordStore.addRecord(context, RawRecord("acc1", now - 3600_000L, "CNY", 100f, 0f, 100f))
    RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 90f, 0f, 90f))

    val viewModel = InsightsViewModel(ApplicationProvider.getApplicationContext() as Application)
    // Wait for state...

    val state = viewModel.uiState.value
    assertNotNull(state.intradayOutput)
    assertNotNull(state.dailyOutput)
    assertEquals(10f, state.intradayBill.consumed, 0.01f)
}
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew.bat assembleDebug --no-daemon 2>&1 | tail -10
```
预期: BUILD SUCCESSFUL

- [ ] **Step 5: 运行全部测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon 2>&1 | tail -10
```

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/example/deepseekbalance/ui/viewmodel/InsightsViewModel.kt
git add app/src/main/java/com/example/deepseekbalance/ui/screen/InsightsScreen.kt
git add app/src/test/java/com/example/deepseekbalance/ui/viewmodel/InsightsViewModelTest.kt
git commit -m "feat: rewrite InsightsViewModel and InsightsScreen for dual-engine architecture"
```

---

### Task 8: 删除旧文件 + 最终验证

**Files:**
- Delete: `app/src/main/java/com/example/deepseekbalance/data/engine/InsightEngine.kt`
- Delete: `app/src/main/java/com/example/deepseekbalance/data/engine/InsightModels.kt`
- Delete: `app/src/test/java/com/example/deepseekbalance/data/engine/InsightEngineTest.kt`

- [ ] **Step 1: 删除旧文件**

```bash
rm app/src/main/java/com/example/deepseekbalance/data/engine/InsightEngine.kt
rm app/src/main/java/com/example/deepseekbalance/data/engine/InsightModels.kt
rm app/src/test/java/com/example/deepseekbalance/data/engine/InsightEngineTest.kt
```

- [ ] **Step 2: 编译 Debug APK**

```bash
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.19_10"
export ANDROID_HOME="$HOME/Android/Sdk"
cd /c/Users/Administrator/DeepSeekBalance
./gradlew.bat assembleDebug --no-daemon 2>&1 | tail -15
```
预期: BUILD SUCCESSFUL

- [ ] **Step 3: 运行全部单元测试**

```bash
./gradlew.bat testDebugUnitTest --no-daemon 2>&1 | tail -15
```
预期: BUILD SUCCESSFUL，测试总数应在 ~150+（删除旧的 21 tests + 新增约 30+ tests）

- [ ] **Step 4: 验证 DataExporter 仍可编译**

```bash
./gradlew.bat assembleDebug --no-daemon 2>&1 | grep -i "error\|FAILED"
```
预期: 无错误

- [ ] **Step 5: 提交**

```bash
git rm app/src/main/java/com/example/deepseekbalance/data/engine/InsightEngine.kt
git rm app/src/main/java/com/example/deepseekbalance/data/engine/InsightModels.kt
git rm app/src/test/java/com/example/deepseekbalance/data/engine/InsightEngineTest.kt
git commit -m "chore: remove old InsightEngine, replaced by IntradayEngine + DailyEngine"
```
