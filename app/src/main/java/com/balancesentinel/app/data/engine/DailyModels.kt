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
    val insufficientData: Boolean = false   // 数据不足（0 个有消耗的数据点），无法计算趋势预估
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
 * 消耗预估方法枚举 — 结构化表示计算方式，UI 层通过 resolve() 解析为本地化字符串。
 */
enum class EstimateMethod {
    LINEAR_REGRESSION,
    AVERAGE,
    SIMPLE_COUNT,
    MULTI_ACCOUNT_LINEAR_REGRESSION,
    MULTI_ACCOUNT_AVERAGE,
    MULTI_ACCOUNT_SIMPLE_COUNT;

    fun resolve(context: android.content.Context, days: Int): String {
        val resId = when (this) {
            LINEAR_REGRESSION -> com.balancesentinel.app.R.string.estimate_linear_regression
            AVERAGE -> com.balancesentinel.app.R.string.estimate_average
            SIMPLE_COUNT -> com.balancesentinel.app.R.string.estimate_simple_count
            MULTI_ACCOUNT_LINEAR_REGRESSION -> com.balancesentinel.app.R.string.estimate_multi_linear
            MULTI_ACCOUNT_AVERAGE -> com.balancesentinel.app.R.string.estimate_multi_average
            MULTI_ACCOUNT_SIMPLE_COUNT -> com.balancesentinel.app.R.string.estimate_multi_simple
        }
        return context.getString(resId, days)
    }
}

/**
 * 消耗预估（基于 consumed 值线性回归）。
 * null = 数据不足或消耗趋近于零。
 */
data class DepletionEstimate(
    val dailyRate: Float,
    val daysRemaining: Float,
    val depletionMonth: Int,
    val depletionDay: Int,
    val method: EstimateMethod,
    val methodDays: Int
)
