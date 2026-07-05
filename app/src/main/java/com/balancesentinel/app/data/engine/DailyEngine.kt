package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

        // Build today's point from raw records BEFORE checking window emptiness.
        // This ensures Day 1 (no summaries yet) still shows today's live data.
        val today = dateFormat.format(Date())
        val todayFiltered = input.todayRawRecords
            .filter { it.currency == input.filterCurrency }
            .filter { input.filterAccountId == null || it.accountId == input.filterAccountId }
            .sortedBy { it.timestamp }

        val todayPoint: DailyPoint? = if (todayFiltered.size >= 1) {
            val todayTopUp = computeTopUpFromRecords(todayFiltered)
            val todayGrant = computeGrantFromRecords(todayFiltered)
            val first = todayFiltered.first()
            val last = todayFiltered.last()
            val consumed = (first.totalBalance - last.totalBalance + todayTopUp + todayGrant)
                .coerceAtLeast(0f)
            DailyPoint(
                date = today,
                balance = last.totalBalance,
                consumed = consumed,
                toppedUp = todayTopUp,
                granted = todayGrant,
                isGapFill = false
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
                    summary.toppedUp, summary.granted, summary.sampleCount == 0
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
        val insufficientData = withConsumption.size < 3

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
     * Requires at least 3 data points and a positive slope.
     * Returns null if data is insufficient or consumption is flat/declining.
     */
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
        } catch (_: Exception) {
            "—"
        }

        return DepletionEstimate(
            dailyRate = slope,
            daysRemaining = daysRemaining,
            depletionDate = depletionDate,
            methodLabel = "基于最近${rangeDays}天消耗数据线性回归"
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

    /**
     * Per-pair top-up detection on raw records (same logic as IntradayEngine).
     * Sums toppedUpBalance deltas that are >=1 and near-integer across adjacent pairs.
     */
    private fun computeTopUpFromRecords(sorted: List<RawRecord>): Float {
        if (sorted.size < 2) return 0f
        var total = 0f
        for (i in 1 until sorted.size) {
            val delta = sorted[i].toppedUpBalance - sorted[i - 1].toppedUpBalance
            if (delta >= 1f && isNearInteger(delta)) {
                total += delta
            }
        }
        return total
    }

    /**
     * Per-pair grant detection on raw records (same logic as IntradayEngine).
     * Sums grantedBalance deltas > 0 across adjacent pairs.
     */
    private fun computeGrantFromRecords(sorted: List<RawRecord>): Float {
        if (sorted.size < 2) return 0f
        var total = 0f
        for (i in 1 until sorted.size) {
            val delta = sorted[i].grantedBalance - sorted[i - 1].grantedBalance
            if (delta > 0f) {
                total += delta
            }
        }
        return total
    }

    private fun isNearInteger(value: Float): Boolean {
        val frac = value - value.toLong().toFloat()
        return frac < 0.01f || frac > 0.99f
    }
}
