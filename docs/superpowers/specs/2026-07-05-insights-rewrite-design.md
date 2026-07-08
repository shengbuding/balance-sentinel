# 洞察功能重写 — 设计文档

日期: 2026-07-05
状态: 已实现 (v1.1.0+)

---

## 1. 概述

放弃 v1.6.5 的 InsightEngine + RecordAggregator 统一窗口方案，重写为双引擎架构。核心变化：

- **旧方案**: 历史日摘要 + 今日原始记录混合在统一窗口中计算，RawRecord 只保留当日
- **新方案**: 24小时滑动窗口和长期日历天完全分离，RawRecord 保留 ≥24 小时

## 2. 架构

```
                    BalanceRefreshService
                           │
                           ▼
                    RawRecordStore ──────────────┐
                   (最多 90,000 条)               │
                   保留 ≥24h，批量清理             │
                           │                     │
              ┌────────────┼────────────┐         │
              ▼            ▼            ▼         │
        IntradayEngine  DailyEngine  RecordAggregator
        (24h 滑动窗口)   (长期日历天)  (Raw→Daily)  │
              │            │            │         │
              ▼            ▼            ▼         │
        InsightViewModel (编排层)        │         │
              │                         │         │
              ▼                         ▼         │
        InsightsScreen             DailySummaryStore
         ├─ 24h 卡片                        │     │
         └─ 长期卡片                        │     │
                                            │     │
              CleanupScheduler ◄────────────┘─────┘
              (午夜闹钟 + App启动)
              聚合 → 补零 → 批量删除
```

### 2.1 模块职责

| 模块 | 类型 | 职责 |
|------|------|------|
| RawRecordStore | 改造 | 保留 ≥24h，批量清理，90k 上限 |
| DailySummaryStore | 改造 | 今日实时覆盖，历史冻结，自动补零 |
| IntradayEngine | 新增 | 24h 滑动窗口，相邻点余额+API差值分析，充值检测 |
| DailyEngine | 新增 | 长期日历天，基于 consumed 的消耗预估 |
| RecordAggregator | 保留 | Raw→Daily 聚合公式，逻辑不变 |
| CleanupScheduler | 新增 | 午夜 + 启动两个触发点，聚合后批量删除 |
| InsightsViewModel | 重写 | 编排 IntradayEngine + DailyEngine |
| InsightsScreen | 重写 | 24h 卡片 + 长期卡片双视图 |

### 2.2 删除文件

| 文件 | 原因 |
|------|------|
| `data/engine/InsightEngine.kt` | 被 IntradayEngine + DailyEngine 替代 |
| `data/engine/InsightModels.kt` | 模型拆分到各引擎 |
| `data/engine/InsightEngineTest.kt` | 替换为新引擎测试 |

---

## 3. 存储层

### 3.1 RawRecordStore 改造

保留现有 SharedPreferences + JSON 方案。核心变更：

**不再跨天清空**。记录保留 ≥24 小时，仅在聚合后批量删除。

```kotlin
// 新增方法
fun getRecordsSince(context: Context, timestamp: Long): List<RawRecord>
    // 24h 滑动窗口查询：it.timestamp >= timestamp

fun getRecordsForDate(context: Context, date: String): List<RawRecord>
    // 按日历日查询（用于每日聚合）

fun getOldUngroupedDates(context: Context): List<String>
    // 扫描所有 >24h 且对应日期无 DailySummary 的记录，返回日期列表

fun removeByDate(context: Context, date: String)
    // 删除指定日期的所有记录（仅删除年龄 >24h 的）
```

**删除约束**（AND 关系）：
- (a) 记录年龄 > 24 小时
- (b) 该日期 DailySummary 已存在
- (c) 该日期 ≠ 今天

**删除只在两个时机批量执行**：
1. 午夜聚合完成后
2. App 启动补汇完成后

不做实时删除判定。

### 3.2 DailySummaryStore 改造

```kotlin
// 新增方法
fun upsert(context: Context, summary: DailySummary)
    // 添加或覆盖（date+currency+accountId 唯一）

fun getSummariesInRange(context: Context, from: String, to: String): List<DailySummary>

fun hasSummaryForDate(context: Context, date: String, currency: String, accountId: String): Boolean

fun ensureContinuity(context: Context, fromDate: String, toDate: String)
    // 前置条件: fromDate 对应的 DailySummary 必须存在（调用方保证）
    //   DailySummaryStore 为空时不应调用此方法
    // 算法:
    //   1. 找到 fromDate 对应条目，取其 close 作为 carryBalance
    //   2. 从 fromDate+1 遍历到 toDate（不含今日）:
    //       若某日无 DailySummary → 插入补零条目
    //         (date=该日, open=carryBalance, close=carryBalance,
    //          consumed=0, toppedUp=0, granted=0, sampleCount=0)
    //       carryBalance 保持不变（无活动日余额不变）
    //   3. 不补今日（今日由实时刷新维护）
    // 补零日: open/close = 前一有效日收盘值, consumed/toppedUp/granted = 0, sampleCount = 0
```

**今日条目**：每次刷新后实时覆盖写入（`upsert`），非今日条目一旦生成即冻结。

---

## 4. RecordAggregator

保留现有 `aggregate()` 逻辑不变（computeToppedUp / computeGranted / computeConsumed）。仅移除 InsightEngine 对它的依赖关系。

---

## 5. IntradayEngine — 24 小时滑动窗口

### 5.1 输入

```kotlin
data class IntradayInput(
    val rawRecords: List<RawRecord>,      // 最近 24h 的原始记录
    val filterCurrency: String,
    val filterAccountId: String?
)
```

### 5.2 输出

```kotlin
data class IntradayOutput(
    val trendPoints: List<IntradayPoint>,
    val billReport: IntradayBillReport,
    val dataPointCount: Int
)

data class IntradayPoint(
    val timestamp: Long,        // 刷新时刻
    val actualBalance: Float,   // 余额
    val isTopUp: Boolean,       // 该点是否检测到充值
    val isGrant: Boolean,       // 该点是否检测到赠送
    val topUpAmount: Float,     // 充值金额（0 表示无）
    val grantAmount: Float      // 赠送金额（0 表示无）
)

data class IntradayBillReport(
    val consumed: Float,        // 真实消耗总额
    val toppedUp: Float,        // 充值总额
    val granted: Float,         // 赠送总额
    val netChange: Float        // toppedUp + granted - consumed
)
```

### 5.3 算法

```
Step 1: 过滤（币种 + 账户）+ 按时间戳 ASC 排序

Step 2: 取最近 24h: records.filter { it.timestamp >= now - 24 * 3600 * 1000 }

Step 3: 逐对分析（prev, curr）相邻刷新点:
    balanceDelta = curr.totalBalance - prev.totalBalance
    topUpDelta   = curr.toppedUpBalance - prev.toppedUpBalance
    grantDelta   = curr.grantedBalance - prev.grantedBalance

    充值判定:
      if topUpDelta >= 1f && topUpDelta 接近整数 (小数部分 < 0.01):
        isTopUp = true, topUpAmount = topUpDelta

    赠送判定:
      if grantDelta > 0:
        isGrant = true, grantAmount = grantDelta

    真实消耗:
      当净增(balanceDelta > 0) 且小于 (topUpDelta + grantDelta) 时:
        consumption = topUpDelta + grantDelta - max(balanceDelta, 0)
      当净降(balanceDelta <= 0) 时:
        consumption = abs(balanceDelta) + topUpDelta + grantDelta
      → 统一公式: consumption = max(0, topUpDelta + grantDelta - balanceDelta)
      → 等同于: 总充值 - 净余额变化，下限 0

Step 4: 汇总
    totalConsumed  = Σ consumption
    totalToppedUp  = Σ topUpAmount
    totalGranted   = Σ grantAmount
    netChange      = totalToppedUp + totalGranted - totalConsumed

边缘情况:
  当 balanceDelta > (topUpDelta + grantDelta) 时，余额净增超过 API 报告的
  充值+赠送总额（可能因 API 数据延迟），公式给出 consumption = 0。
  正确行为：无证据表明发生了消耗，不应捏造消耗值。差额不归入任何类别。
```

### 5.4 图表

- X 轴智能抽稀：按信息密度分配宽度，而非按点数均分
- 时间段内点密集处不压缩其他时间段
- 曲线 Catmull-Rom 平滑
- 充值点标记 ▲（绿色），赠送点标记 ◆（紫色）
- X 轴标签格式 HH:mm，根据显示空间自动决定抽稀粒度

---

## 6. DailyEngine — 长期视图

### 6.1 输入

```kotlin
data class DailyInput(
    val summaries: List<DailySummary>,     // 全部日摘要（含自动补零）
    val todayRawRecords: List<RawRecord>,  // 今日原始记录（实时聚合用）
    val filterCurrency: String,
    val filterAccountId: String?,
    val rangeDays: Int                     // 7 / 14 / 30 / 90 / 365
)
```

### 6.2 输出

```kotlin
data class DailyOutput(
    val dailyPoints: List<DailyPoint>,
    val billReport: DailyBillReport,
    val estimate: DepletionEstimate?,
    val periodLabel: String,
    val isEmpty: Boolean
)

data class DailyPoint(
    val date: String,           // yyyy-MM-dd
    val balance: Float,         // 收盘余额
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val isGapFill: Boolean      // 是否为补零日（sampleCount == 0）
)

data class DailyBillReport(
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val netChange: Float,
    val periodLabel: String
)
```

### 6.3 算法

```
Step 1: 过滤 + 取时间窗口 summaries.takeLast(rangeDays)

Step 2: 今日特殊处理
    → 用 RecordAggregator 将 todayRawRecords 实时聚合
    → 覆写到 summaries 中的今日条目
    → 历史条目直接使用（已冻结）

Step 3: 汇总
    totalConsumed = Σ dailyPoint.consumed
    totalToppedUp = Σ dailyPoint.toppedUp
    totalGranted  = Σ dailyPoint.granted

Step 4: 消耗预估（基于 consumed 值）
    对 (dateIndex, consumed) 做线性回归
    dailyRate = 回归斜率（若斜率接近0或为负则取日均值）
    daysRemaining = lastBalance / dailyRate
    depletionDate = 当前日期 + daysRemaining

    null 条件:
      - 数据点 < 3
      - dailyRate <= 0（消耗趋近于0或增长）

Step 5: 自动补零
    检测 DailySummaryStore 中日期连续性
    间隙日插入: open/close = 前一个有效日收盘值, consumed/toppedUp/granted = 0
    sampleCount = 0 → isGapFill = true（图表灰显或虚线）
```

### 6.4 图表

- 折线图，每天一个点，X 轴均匀分布
- 充值日标记 ▲（绿色）
- 补零日以虚线连接前后有效点，或灰点表示
- 时间范围切换：7天 / 14天 / 30天 / 90天 / 365天

| 范围 | 标签 | X 轴格式 |
|------|------|---------|
| 7天 | 最近7天 | MM-DD |
| 14天 | 最近14天 | MM-DD |
| 30天 | 最近30天 | MM-DD（紧凑） |
| 90天 | 最近90天 | M月D日 |
| 365天 | 最近1年 | YYYY-MM |

---

## 7. CleanupScheduler

### 7.1 触发点

**Trigger 1 + Trigger 2: 统一清理流程**

午夜闹钟和 App 启动执行相同的逻辑，互为冗余：

```
MidnightReceiver / DeepSeekApp 触发:
  → dates = RawRecordStore.getOldUngroupedDates()
      // 扫描所有年龄 >24h 且对应日期无 DailySummary 的记录
  → for date in dates:
      records = RawRecordStore.getRecordsForDate(date)
      RecordAggregator.aggregate(records) → DailySummaryStore.upsert()
  → // 所有聚合完成后，一次性补零
  → lastExistingDate = DailySummaryStore 中最新条目的日期（不含今日）
  → if lastExistingDate != null:
      DailySummaryStore.ensureContinuity(lastExistingDate, 昨天)
  → // 最后统一删除
  → for date in dates:
      RawRecordStore.removeByDate(date)  // 仅删年龄 >24h 的
```

**运行时（每次刷新后）**：不触发任何删除，只写入 RawRecord + 更新今日 DailySummary。

### 7.2 数日未启动

```
场景: 7月1日关闭App（30条记录），7月2-3日未运行，7月4日启动

App启动触发（统一清理逻辑）:
  → getOldUngroupedDates() 返回: ["2026-07-01"]
  → 聚合 7月1日 30条 → DailySummary("2026-07-01") 写入
  → lastExistingDate = "2026-07-01"
  → yesterday = "2026-07-03"
  → ensureContinuity("2026-07-01", "2026-07-03")
    → 7月2日 缺失 → 补零 (carryBalance=7月1日收盘值)
    → 7月3日 缺失 → 补零 (carryBalance=7月1日收盘值)
  → removeByDate("2026-07-01") → 30条记录全部 >72h ✓ 全部删除

结果:
  DailySummary: 7/1(真实数据), 7/2(补零), 7/3(补零), 7/4(实时)
  RawRecord: 仅保留7月4日记录（<24h 或今日）
```

---

## 8. InsightsScreen UI

### 8.1 布局

```
┌─────────────────────────────┐
│ 币种 Tab                    │
│ 账户筛选                    │
├─────────────────────────────┤
│                             │
│  ▼ 24小时  ───────────────  │  ← 卡片1: IntradayEngine 输出
│  [趋势图: 智能抽稀 X 轴]    │
│  [账单: 消耗 | 充值 | 赠送 | 净变化] │
│                             │
├─────────────────────────────┤
│                             │
│  ▼ 长期  ──────────────────  │  ← 卡片2: DailyEngine 输出
│  [7天|14天|30天|90天|年]    │
│  [趋势图: 均匀日 X 轴]      │
│  [账单: 消耗 | 充值 | 赠送 | 净变化] │
│  [消耗预估: 日均 | 剩余天数 | 耗尽日] │
│                             │
└─────────────────────────────┘
```

### 8.2 交互

- 币种 Tab 和账户筛选对两个卡片同时生效
- 24h 卡片始终显示最近 24 小时，无需时间范围选择
- 长期卡片提供 FilterChip 切换时间范围 → 触发 DailyEngine 重算
- 下拉刷新 → 重新加载两个引擎

---

## 9. 测试策略

| 测试类 | 类型 | 覆盖 |
|--------|------|------|
| IntradayEngineTest | 纯 JVM | 空输入、单记录、正常消耗、充值检测（整数/小数过滤）、混合场景（消耗+充值同次刷新间）、赠送检测、币种/账户过滤、24h 窗口边界 |
| DailyEngineTest | 纯 JVM | 空输入、单日、多日、消耗预估（上升/下降/稳定）、补零日处理、范围切换、今日实时覆盖 |
| RecordAggregatorTest | 纯 JVM | 保留现有 13 tests，验证公式不变 |
| RawRecordStoreTest | Robolectric | >24h 过滤、按日期查询、批量删除、未汇总日期扫描 |
| DailySummaryStoreTest | Robolectric | upsert 覆盖、范围查询、连续性补零、hasSummaryForDate |
| CleanupSchedulerTest | Robolectric | 午夜聚合流程、启动补汇流程、数日未启动场景 |
| InsightsViewModelTest | Robolectric | 编排逻辑、引擎切换、币种/账户变更触发重算 |

---

## 10. Token 用量

Token 用量（UsageDataStore / UsageSnapshot）不在本次重写范围内。旧 InsightScreen 中的 TokenUsageCard 暂时移除。后续可基于 DailyEngine 的窗口期独立实现 Token 统计卡片。

---

## 11. 澄清记录

以下为头脑风暴阶段确认的设计决策：

| 决策 | 结论 |
|------|------|
| 24h 窗口定义 | 从当前时刻往前推 24 小时的滑动窗口 |
| 充值检测方式 | 相邻点 toppedUpBalance 累计差值，辅以余额净变化 |
| 充值精度 | >= 1 且为整数（小数部分 < 0.01） |
| 混合场景拆分 | API toppedUpBalance/grantedBalance 差值可精确拆分 |
| 历史数据 | 非今日日摘要一旦生成即冻结 |
| 日期连续性 | 自动补零，sampleCount=0 标记 |
| 记录保留 | 最低 24h，仅在午夜聚合/启动补汇后批量删除 |
| 删除优化 | 不实时判定，聚合后批量执行 |
| 图表抽稀 | 24h: 按信息密度而非点数均分; 长期: 均匀日 |
| 长期范围 | 7天/14天/30天/90天/365天 |
| 消耗预估 | 基于 consumed 值线性回归，非净余额 |
| 数据模型 | RawRecord 和 DailySummary 字段不变，向后兼容 |

---

## 12. 与旧方案的差异总结

| | 旧方案 (v1.6.5) | 新方案 |
|---|---|---|
| 24h 视图 | 与历史混合在统一窗口 | 独立引擎，滑动 24h 窗口 |
| 充值检测 | 日内差值 + 跨天绝对值比较 | 相邻点 toppedUpBalance 累计差值 |
| 充值精度 | 需 ≥2 条同日记录 | 相邻刷新对即可，>=1 整数检测 |
| 消耗计算 | 日聚合公式 | 逐对实时计算，充值不影响消耗 |
| RawRecord 保留 | 仅当日 | ≥24h，最多 90k |
| 删除时机 | clear() 全毁 / removeRecords 精确删 | 聚合后批量删（午夜 + 启动） |
| 历史数据 | 可被今日覆盖 | 非今日冻结，永不修改 |
| 日期连续性 | 无保证 | 自动补零 |
| 消耗预估 | 基于净余额回归 | 基于 consumed 值回归 |
| 图表 X 轴 | 智能 MM-DD/HH:mm | 24h: 信息密度抽稀; 长期: 均匀日 |
