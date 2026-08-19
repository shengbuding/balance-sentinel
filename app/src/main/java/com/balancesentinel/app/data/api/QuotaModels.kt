package com.balancesentinel.app.data.api

import kotlinx.serialization.Serializable

/** Stable currency marker used for usage-percentage quotas. */
const val PERCENTAGE_CURRENCY = "%"

/** Sentinel used in the legacy three-number history columns when a period is absent. */
const val UNKNOWN_QUOTA_REMAINING = -1.0

/**
 * A quota window returned by a provider usage endpoint.
 *
 * `id` is intentionally a string so custom providers can add windows without an
 * application release. Known aliases are normalized by [quotaPeriodRank].
 */
@Serializable
data class QuotaPeriodSnapshot(
    val id: String,
    val usedPercent: Double,
    val remainingPercent: Double,
    val resetsAt: String? = null,
    val status: String? = null
) {
    init {
        require(usedPercent.isFinite() && remainingPercent.isFinite())
        require(usedPercent in 0.0..100.0 && remainingPercent in 0.0..100.0)
    }
}

@Serializable
data class QuotaSnapshot(
    val periods: List<QuotaPeriodSnapshot> = emptyList()
) {
    fun find(id: String): QuotaPeriodSnapshot? = periods.firstOrNull { period ->
        val expected = id.trim().lowercase()
        val actual = period.id.trim().lowercase()
        if (quotaPeriodRank(expected) >= 100 || quotaPeriodRank(actual) >= 100) {
            actual == expected
        } else {
            quotaPeriodRank(actual) == quotaPeriodRank(expected)
        }
    }

    fun remaining(id: String): Double = find(id)?.remainingPercent ?: UNKNOWN_QUOTA_REMAINING

    fun ordered(): List<QuotaPeriodSnapshot> = periods
        .distinctBy { it.id.trim().lowercase() }
        .sortedWith(compareBy({ quotaPeriodRank(it.id) }, { it.id }))
}

/** Returns a stable order for the built-in windows and a deterministic fallback for custom ones. */
fun quotaPeriodRank(id: String): Int = when (id.trim().lowercase().replace('-', '_')) {
    "rolling", "rolling_5h", "rolling_5_hour", "5h", "5_hour", "five_hours" -> 0
    "weekly", "week", "7d", "7_day" -> 1
    "monthly", "month", "30d", "30_day" -> 2
    else -> 100
}

fun QuotaPeriodSnapshot.isKnownWindow(id: String): Boolean =
    if (quotaPeriodRank(this.id) >= 100 || quotaPeriodRank(id) >= 100) {
        this.id.trim().equals(id.trim(), ignoreCase = true)
    } else {
        quotaPeriodRank(this.id) == quotaPeriodRank(id)
    }
