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
    val isEmpty: Boolean,
    val insufficientData: Boolean = false   // 数据不足（<3 个有消耗的数据点），无法计算趋势预估
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
    val isGapFill: Boolean,
    val open: Float = 0f,
    val sampleCount: Int = 0
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
