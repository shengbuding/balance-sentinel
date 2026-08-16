package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.roundToInt

object DailyEngine {

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun compute(input: DailyInput): DailyOutput {
        val filtered = input.summaries
            .filter { it.currency == input.filterCurrency }
            .filter { input.filterAccountId == null || it.accountId == input.filterAccountId }
            .sortedBy { it.date }

        val window = filtered.takeLast(input.rangeDays)
        val periodLabel = buildPeriodLabel(input.rangeDays)

        // Build today's point from raw records BEFORE checking window emptiness.
        // This ensures Day 1 (no summaries yet) still shows today's live data.
        val today = LocalDate.now().format(dateFormatter)
        val todayFiltered = input.todayRawRecords
            .filter { it.currency == input.filterCurrency }
            .filter { input.filterAccountId == null || it.accountId == input.filterAccountId }
            .sortedBy { it.timestamp }

        val todayPoint: DailyPoint? = if (todayFiltered.size >= 1) {
            val todayTopUp = computeTopUpFromRecords(todayFiltered)
            val todayGrant = computeGrantFromRecords(todayFiltered)
            val first = todayFiltered.first()
            val last = todayFiltered.last()
            val consumed = computeConsumedFromPairs(todayFiltered)
            DailyPoint(
                date = today,
                balance = last.totalBalance,
                consumed = consumed,
                toppedUp = todayTopUp,
                granted = todayGrant,
                isGapFill = false,
                open = first.totalBalance,
                sampleCount = todayFiltered.size
            )
        } else null

        // Both summaries and today's raw records are empty → truly insufficient
        if (window.isEmpty() && todayPoint == null) {
            return DailyOutput(
                emptyList(),
                DailyBillReport(0f, 0f, 0f, 0f, periodLabel),
                null,
                periodLabel,
                true,
                insufficientData = true
            )
        }

        // summaries empty but today has live data → Day 1 chart with a single point
        if (window.isEmpty()) {
            val point = todayPoint!!
            val dailyPoints = listOf(point)
            return DailyOutput(
                dailyPoints = dailyPoints,
                billReport = DailyBillReport(
                    point.consumed, point.toppedUp, point.granted,
                    point.toppedUp + point.granted - point.consumed, periodLabel
                ),
                estimate = null, // can't estimate from a single point
                periodLabel = periodLabel,
                isEmpty = false,
                insufficientData = true  // only 1 point, not enough for trend analysis
            )
        }

        // Normal path: merge summaries with today's live point
        val dailyPoints = window.map { summary ->
            if (summary.date == today && todayPoint != null) {
                todayPoint
            } else {
                DailyPoint(
                    summary.date, summary.close, summary.consumed,
                    summary.toppedUp, summary.granted, summary.sampleCount == 0,
                    summary.open, summary.sampleCount
                )
            }
        }.toMutableList()

        // Ensure today's point is always included even if no today summary exists yet
        if (todayPoint != null && dailyPoints.none { it.date == today }) {
            dailyPoints.add(todayPoint)
            dailyPoints.sortBy { it.date }
            // Re-apply window limit after adding today
            if (dailyPoints.size > input.rangeDays) {
                dailyPoints.removeAt(0)
            }
        }

        val totalConsumed = dailyPoints.sumOf { it.consumed.toDouble() }.toFloat()
        val totalToppedUp = dailyPoints.sumOf { it.toppedUp.toDouble() }.toFloat()
        val totalGranted = dailyPoints.sumOf { it.granted.toDouble() }.toFloat()

        val estimate = computeDepletionEstimate(dailyPoints, input.rangeDays)
        val withConsumption = dailyPoints.filter { it.consumed > 0f }
        val insufficientData = withConsumption.isEmpty()

        return DailyOutput(
            dailyPoints = dailyPoints,
            billReport = DailyBillReport(
                totalConsumed, totalToppedUp, totalGranted,
                totalToppedUp + totalGranted - totalConsumed, periodLabel
            ),
            estimate = estimate,
            periodLabel = periodLabel,
            isEmpty = false,
            insufficientData = insufficientData
        )
    }

    /**
     * Linear regression on (index, consumed) over points with consumption > 0.
     * Uses linear regression when >= 3 data points with positive slope;
     * falls back to mean rate for 1-2 points.
     * Returns null only when there is zero consumption (balance never decreased).
     */
    private fun computeDepletionEstimate(
        points: List<DailyPoint>,
        rangeDays: Int
    ): DepletionEstimate? {
        val withConsumption = points.filter { it.consumed > 0f }
        if (withConsumption.isEmpty()) return null

        val lastBalance = points.lastOrNull()?.balance ?: return null

        val yValues = withConsumption.map { it.consumed }
        val n = withConsumption.size.toFloat()
        val sumY = yValues.sum()
        val meanRate = sumY / n

        if (meanRate <= 0f) return null

        // 尝试线性回归（需 ≥3 点且分母非零）；否则直接用均值
        val dailyRate: Float
        val method: EstimateMethod
        val methodDays: Int

        if (withConsumption.size >= 3) {
            val xValues = withConsumption.indices.map { it.toFloat() }
            val sumX = xValues.sum()
            val sumXY = xValues.zip(yValues).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
            val sumX2 = xValues.sumOf { (it * it).toDouble() }.toFloat()
            val denominator = n * sumX2 - sumX * sumX

            if (denominator != 0f) {
                val slope = (n * sumXY - sumX * sumY) / denominator
                if (slope > 0f) {
                    dailyRate = slope
                    method = EstimateMethod.LINEAR_REGRESSION
                } else {
                    dailyRate = meanRate
                    method = EstimateMethod.AVERAGE
                }
            } else {
                dailyRate = meanRate
                method = EstimateMethod.AVERAGE
            }
            methodDays = rangeDays
        } else {
            dailyRate = meanRate
            method = EstimateMethod.SIMPLE_COUNT
            methodDays = withConsumption.size
        }

        val daysRemaining = lastBalance / dailyRate

        val (depletionMonth, depletionDay) = try {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, daysRemaining.roundToInt())
            (cal.get(Calendar.MONTH) + 1) to cal.get(Calendar.DAY_OF_MONTH)
        } catch (_: Exception) {
            0 to 0
        }

        return DepletionEstimate(
            dailyRate = dailyRate,
            daysRemaining = daysRemaining,
            depletionMonth = depletionMonth,
            depletionDay = depletionDay,
            method = method,
            methodDays = methodDays
        )
    }

    private fun buildPeriodLabel(days: Int): String = when (days) {
        7 -> "最近7天"
        14 -> "最近14天"
        30 -> "最近30天"
        90 -> "最近90天"
        365 -> "最近1年"
        else -> "最近${days}天"
    }

    /** Per-pair recharge detection shared with the intraday and archive paths. */
    private fun computeTopUpFromRecords(sorted: List<RawRecord>): Float {
        if (sorted.size < 2) return 0f
        var total = 0f
        for (i in 1 until sorted.size) {
            total += topUpAmount(sorted[i - 1], sorted[i])
        }
        return total
    }

    /** Per-pair grant detection shared with the intraday and archive paths. */
    private fun computeGrantFromRecords(sorted: List<RawRecord>): Float {
        if (sorted.size < 2) return 0f
        var total = 0f
        for (i in 1 until sorted.size) {
            total += grantAmount(sorted[i - 1], sorted[i])
        }
        return total
    }

    /**
     * 逐对消费计算 — 与 IntradayEngine / RecordAggregator 一致的会计公式：
     * consumption = (topUpAmount + grantAmount - balanceDelta).coerceAtLeast(0f)。
     */
    private fun computeConsumedFromPairs(sorted: List<RawRecord>): Float {
        var consumed = 0f
        for (i in 1 until sorted.size) {
            consumed += consumedAmount(sorted[i - 1], sorted[i])
        }
        return consumed
    }
}
