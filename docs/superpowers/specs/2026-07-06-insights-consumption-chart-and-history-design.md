# 洞察页 — 消耗图表切换 + 历史日汇总

日期: 2026-07-06
状态: 待审查

---

## 1. 概述

在洞悉页面长期趋势模块中新增两个能力：

1. **消耗折线图切换** — 用户可在「余额」和「消耗」两种图表模式之间切换，复用现有 DailyLineChart
2. **历史日汇总卡片** — 在长期趋势卡片下方新增独立卡片，以列表形式展示每日数据，支持展开查看完整字段和分页加载

---

## 2. 模块一：消耗折线图切换

### 2.1 UI

- 在 DailyCard 图表上方、时间范围 FilterChip 下方，新增一组 **余额 | 消耗** 分段切换
- 样式：两个并排 FilterChip，选中态使用 Primary 色，和现有 7/14/30 FilterChip 风格一致
- 「余额」：默认选中，图表展示每日收盘余额折线（现有行为）
- 「消耗」：图表展示每日消耗金额折线，画法完全一致

### 2.2 图表行为差异

| | 余额模式 | 消耗模式 |
|---|---|---|
| Y 轴数据源 | `DailyPoint.balance` | `DailyPoint.consumed` |
| 折线 + 渐变填充 | ✓ | ✓ |
| 最高/最低/当前横虚线 | ✓ | ✓ |
| 充值 ▲ 标记 | ✓ | 隐藏 |
| 赠送 ◆ 标记 | ✓ | 隐藏 |
| 补零日灰点虚线 | ✓ | 消耗=0 的天不标记（与余额不同） |
| 账单报表 | 不变 | 不变（仍显示期间汇总） |

- 消耗模式下的横虚线标注含义变化：
  - 最高点 = "消耗最多的一天"
  - 最低点 = "消耗最少的一天"
  - 当前点 = "今日消耗"

### 2.3 数据模型

`InsightsUiState` 新增字段：

```kotlin
val chartMode: String = "balance"   // "balance" | "consumed"
```

ViewModel 新增方法：

```kotlin
fun setChartMode(mode: String)  // 仅更新 UI state，不触发数据重载
```

- 切换在纯 UI 层完成：`DailyPoint` 数据已在内存中，切换只改变图表读取的字段
- 不需要重新调用 DailyEngine

### 2.4 数据层

不动。`DailyPoint.consumed` 字段已存在。

---

## 3. 模块二：历史日汇总卡片

### 3.1 UI 布局

新组件 `DailyHistoryCard`，位于 DailyCard 下方，作为第三张卡片。

```
┌─────────────────────────────────┐
│  历史日汇总                      │
│                                 │
│  2026-07-06  -¥1.23  ¥45.67  ▶  │  ← 折叠行
│  2026-07-05  -¥0.89  ¥46.90  ▶  │
│  2026-07-04   —      ¥47.79     │  ← 消耗=0，灰色，不可展开
│  2026-07-03  -¥2.10  ¥47.79  ▼  │  ← 展开态
│    ┌────────────────────────┐   │
│    │ 消耗      -¥2.10       │   │
│    │ 充值      +¥0.00       │   │
│    │ 赠送      +¥0.00       │   │
│    │ 净变化    -¥2.10       │   │
│    │ 开盘余额  ¥49.89       │   │
│    │ 收盘余额  ¥47.79       │   │
│    │ 采样次数  12           │   │
│    └────────────────────────┘   │
│  2026-07-02  -¥1.50  ¥49.89  ▶  │
│                                 │
│         [ 加载更多 (10天) ]      │
└─────────────────────────────────┘
```

### 3.2 行设计

**折叠行：**
- 格式：`yyyy-MM-dd` + 消耗金额（红色，前缀 `-`）+ 当日余额 + 展开箭头 `▶`
- 消耗 = 0（补零日）：灰色文字，无箭头，不可展开
- 日期格式：`yyyy-MM-dd`（如 2026-07-06）

**展开行：**
- 箭头变为 `▼`
- 下方展开区域显示完整数据，Indent 缩进，浅色背景区分
- 字段列表：消耗 / 充值 / 赠送 / 净变化 / 开盘余额 / 收盘余额 / 采样次数
- 每行左右对齐（label 左，value 右），颜色语义与现有账单报表一致

### 3.3 分页

- 默认显示最近 7 天
- 「加载更多」按钮每次 +10 天
- 全部加载完毕后按钮消失，替换为「已加载全部」提示文字
- 数据源：`DailyOutput.dailyPoints`，倒序排列（最新在前）

### 3.4 数据模型

`InsightsUiState` 新增字段：

```kotlin
val historyVisibleCount: Int = 7,
val expandedDate: String? = null      // 当前展开的日期，null = 全部折叠
```

`DailyPoint` 新增字段（用于展开区域的完整数据）：

```kotlin
val open: Float = 0f,           // 开盘余额
val sampleCount: Int = 0        // 当日采样次数
```

ViewModel 新增方法：

```kotlin
fun loadMoreHistory()            // historyVisibleCount += 10，上限 = dailyPoints.size
fun toggleExpandDate(date: String)  // 切换展开/折叠
```

### 3.5 数据层

`DailyEngine.compute()` 在构造 `DailyPoint` 时从 `DailySummary` 读取 `open` 和 `sampleCount` 并传入。历史条目直接取 summary 值；今日实时条目 `open` = 当日首条记录余额，`sampleCount` = 当日记录数。

---

## 4. 测试策略

| 测试 | 内容 |
|------|------|
| InsightsViewModelTest | chartMode 切换不触发重载 / historyVisibleCount 分页上限 / toggleExpandDate |
| InsightsScreenTest (Android) | 消耗图表切换渲染 / 历史汇总列表展开折叠 / 加载更多按钮显隐 |
| DailyEngineTest | DailyPoint.open 和 sampleCount 字段正确填充 |

---

## 5. 涉及文件

| 文件 | 改动 |
|------|------|
| `ui/screen/InsightsScreen.kt` | DailyCard 新增 chartMode 切换 + 图表数据源分支；新增 DailyHistoryCard 组件 |
| `ui/viewmodel/InsightsViewModel.kt` | 新增 setChartMode / loadMoreHistory / toggleExpandDate；新增 state 字段 |
| `data/engine/DailyModels.kt` | DailyPoint 新增 open、sampleCount 字段 |
| `data/engine/DailyEngine.kt` | 构造 DailyPoint 时传入 open、sampleCount |
| 测试文件 | 新增对应 test case |

---

## 6. 澄清记录

| 决策 | 结论 |
|------|------|
| 消耗图表形式 | 与余额图表相同的折线图，仅数据源不同 |
| 图表切换方式 | 图表上方 FilterChip 分段切换（余额 \| 消耗） |
| 历史汇总展示 | 折叠列表，默认 7 天，点击展开完整数据 |
| 日期格式 | yyyy-MM-dd |
| 展开内容 | 消耗/充值/赠送/净变化/开盘余额/收盘余额/采样次数 |
| 分页增量 | 每次 +10 天 |
| 补零日行为 | 灰色文字，不可展开 |
| 数据复用 | 不新增存储查询，复用 DailyEngine 输出 |
