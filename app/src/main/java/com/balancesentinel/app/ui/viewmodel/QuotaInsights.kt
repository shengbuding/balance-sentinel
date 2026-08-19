package com.balancesentinel.app.ui.viewmodel

/** One historical quota observation, expressed as percentage already used. */
data class QuotaInsightPoint(
    val timestamp: Long,
    val usedPercent: Float
)

/** Current and historical state for one quota window. */
data class QuotaInsightPeriod(
    val id: String,
    val usedPercent: Float,
    val remainingPercent: Float,
    val resetsAt: String? = null,
    val status: String? = null,
    val history: List<QuotaInsightPoint> = emptyList(),
    /** Account whose latest completed refresh is represented by the live quota. */
    val latestRefreshAccountId: String? = null,
    val latestRefreshAccountLabel: String? = null,
    val latestRefreshAt: Long? = null
)

/** Percentage quota insight shown instead of monetary billing charts. */
data class QuotaInsight(
    val periods: List<QuotaInsightPeriod> = emptyList()
) {
    val isEmpty: Boolean
        get() = periods.isEmpty()
}
