package com.balancesentinel.app.ui.screen

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class InsightsChartAxis(
    val min: Float,
    val max: Float
)

/** A zero-based axis for non-negative balances, while retaining negative data when present. */
fun insightsChartAxis(values: List<Float>): InsightsChartAxis {
    if (values.isEmpty()) return InsightsChartAxis(0f, 1f)
    val dataMin = values.minOrNull() ?: 0f
    val dataMax = values.maxOrNull() ?: 0f
    val axisMin = min(0f, dataMin)
    val visibleMax = max(axisMin, dataMax)
    val rawRange = visibleMax - axisMin
    val headroom = if (rawRange <= 0.0001f) {
        max(1f, abs(visibleMax) * 0.12f)
    } else {
        rawRange * 0.12f
    }
    return InsightsChartAxis(axisMin, visibleMax + headroom)
}

/** Subscription charts always use a fixed 0-100 percent vertical scale. */
fun quotaChartY(percent: Float, top: Float, bottom: Float): Float {
    if (bottom <= top) return top
    return bottom - (bottom - top) * (percent.coerceIn(0f, 100f) / 100f)
}

/** Converts the provider's used percentage into the remaining quota shown by the chart. */
fun quotaChartRemainingPercent(usedPercent: Float): Float =
    (100f - usedPercent).coerceIn(0f, 100f)

/** Uses the refresh represented by the series when no live refresh timestamp exists. */
fun quotaChartCurrentTimestamp(
    currentTimestamp: Long?,
    latestHistoryTimestamp: Long?,
    fallbackNow: Long
): Long = currentTimestamp ?: latestHistoryTimestamp ?: fallbackNow

/** Keeps subscription time axes readable by showing at most start, middle and end. */
fun quotaChartTickIndices(pointCount: Int): List<Int> = when {
    pointCount <= 0 -> emptyList()
    pointCount == 1 -> listOf(0)
    pointCount == 2 -> listOf(0, 1)
    else -> listOf(0, pointCount / 2, pointCount - 1).distinct()
}

/** The latest refreshed account is meaningful only for the all-account view. */
fun shouldShowQuotaLatestRefreshAccount(selectedAccountId: String?): Boolean =
    selectedAccountId == null

data class InsightsChartLabelRequest(
    val id: String,
    val lineY: Float,
    val preferBelow: Boolean,
    val priority: Int = 0
)

data class InsightsChartLabelPlacement(
    val id: String,
    val lineY: Float,
    val baseline: Float
)

/**
 * Places right-side value labels without overlap. A below-line request (the
 * minimum value) is never moved above its guide line; if no legal position is
 * available it is omitted instead of colliding with another label.
 */
fun layoutInsightsChartLabels(
    requests: List<InsightsChartLabelRequest>,
    top: Float,
    bottom: Float,
    textHeight: Float = 34f,
    gap: Float = 8f
): List<InsightsChartLabelPlacement> {
    if (requests.isEmpty() || bottom <= top) return emptyList()
    val minBaseline = top + textHeight
    val maxBaseline = bottom
    val minDistance = textHeight + gap
    val placed = mutableListOf<InsightsChartLabelPlacement>()

    requests
        .sortedWith(compareBy<InsightsChartLabelRequest>({ it.priority }, { it.lineY }))
        .forEach { request ->
            // Equal guide lines are one value, so keep the higher-priority label.
            if (placed.any { abs(it.lineY - request.lineY) < 2f }) return@forEach
            val preferred = if (request.preferBelow) {
                request.lineY + textHeight + gap
            } else {
                request.lineY - gap
            }
            val alternate = if (request.preferBelow) {
                request.lineY - gap
            } else {
                request.lineY + textHeight + gap
            }
            val candidates = buildList {
                repeat(8) { index ->
                    val distance = index * minDistance
                    add(if (request.preferBelow) {
                        request.lineY + textHeight + gap + distance
                    } else {
                        request.lineY - gap - distance
                    })
                }
                repeat(8) { index ->
                    val distance = index * minDistance
                    add(if (request.preferBelow) {
                        request.lineY - gap - distance
                    } else {
                        request.lineY + textHeight + gap + distance
                    })
                }
                // Keep the original alternatives available for very tight bounds.
                add(preferred)
                add(alternate)
            }
            val candidate = candidates
                .map { baseline -> baseline.coerceIn(minBaseline, maxBaseline) }
                .firstOrNull { baseline ->
                    if (request.preferBelow && baseline <= request.lineY) return@firstOrNull false
                    placed.all { abs(it.baseline - baseline) >= minDistance }
                }
            if (candidate != null) {
                placed += InsightsChartLabelPlacement(request.id, request.lineY, candidate)
            }
        }
    return placed
}
