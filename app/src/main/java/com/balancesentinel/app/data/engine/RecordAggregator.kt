package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord

/**
 * 聚合公式的单一真相源。
 *
 * RawRecords → DailySummary 的转换逻辑只在这里定义一次，
 * DailySummaryStore（午夜聚合）和 InsightEngine（今日实时摘要）共用。
 *
 * 纯 Kotlin，无 Android 依赖，可直接在 JVM 单元测试中验证。
 */
object RecordAggregator {

    /**
     * 将 RawRecords 按 (currency, accountId) 分组聚合为 DailySummary。
     *
     * @param records  待聚合的原始记录
     * @param date     摘要日期 ("yyyy-MM-dd")，应取记录时间戳对应的日期，而非调用时的当前时间
     * @return 按 (currency, accountId) 分组的 DailySummary 列表
     */
    fun aggregate(records: List<RawRecord>, date: String): List<DailySummary> {
        if (records.isEmpty()) return emptyList()

        return records
            .groupBy { AggregationKey(it.currency, it.accountId) }
            .mapValues { (_, recs) ->
                val sorted = recs.sortedBy { it.timestamp }
                val toppedUp = computeToppedUp(sorted)
                val granted = computeGranted(sorted)
                val consumed = computeConsumed(sorted)
                DailySummary(
                    accountId = sorted.first().accountId,
                    date = date,
                    currency = sorted.first().currency,
                    open = sorted.first().totalBalance,
                    close = sorted.last().totalBalance,
                    toppedUp = toppedUp,
                    granted = granted,
                    consumed = consumed,
                    avgBalance = sorted.map { it.totalBalance }.average().toFloat(),
                    sampleCount = sorted.size,
                    toppedUpBalanceClose = sorted.last().toppedUpBalance,
                    grantedBalanceClose = sorted.last().grantedBalance,
                    generatedAt = System.currentTimeMillis()
                )
            }
            .values
            .toList()
    }

    /**
     * toppedUp = 累加每次充值/余额补充的正向跳变。
     *
     * DeepSeek exposes a cumulative topped_up_balance field, while many
     * providers (including custom scripts) expose only the current balance.
     * When all metadata fields are absent, a meaningful positive balance delta
     * is the only provider-neutral recharge signal available.
     */
    fun computeToppedUp(sorted: List<RawRecord>): Float {
        var sum = 0f
        for (i in 1 until sorted.size) {
            sum += topUpAmount(sorted[i - 1], sorted[i])
        }
        return sum
    }

    /** granted = 累加每次 grantedBalance 的正向跳变 */
    fun computeGranted(sorted: List<RawRecord>): Float {
        var sum = 0f
        for (i in 1 until sorted.size) {
            sum += grantAmount(sorted[i - 1], sorted[i])
        }
        return sum
    }

    /**
     * 消耗 = 逐对累加 totalBalance 纯下降量。
     *
     * 与 IntradayEngine 一致的会计公式：consumption = (topUpAmount + grantAmount - balanceDelta).coerceAtLeast(0f)。
     * 即使区间有充值/赠送，也能正确计算该区间的消费量。
     */
    fun computeConsumed(sorted: List<RawRecord>): Float {
        var consumed = 0f
        for (i in 1 until sorted.size) {
            consumed += consumedAmount(sorted[i - 1], sorted[i])
        }
        return consumed
    }
}

/**
 * Returns the recharge amount represented by one adjacent record pair.
 * Explicit provider metadata wins. If both metadata fields remain zero, a
 * positive balance delta is treated as a recharge so custom accounts work too.
 */
internal fun topUpAmount(previous: RawRecord, current: RawRecord): Float {
    val explicitDelta = current.toppedUpBalance - previous.toppedUpBalance
    if (explicitDelta >= 1f && isNearInteger(explicitDelta)) return explicitDelta

    val grantDelta = current.grantedBalance - previous.grantedBalance
    val metadataMissing = previous.toppedUpBalance == 0f &&
        current.toppedUpBalance == 0f &&
        previous.grantedBalance == 0f &&
        current.grantedBalance == 0f
    val balanceDelta = current.totalBalance - previous.totalBalance
    return if (
        metadataMissing &&
            grantDelta <= 0f &&
            balanceDelta > INFERRED_TOP_UP_EPSILON
    ) {
        balanceDelta
    } else {
        0f
    }
}

internal fun grantAmount(previous: RawRecord, current: RawRecord): Float =
    (current.grantedBalance - previous.grantedBalance).takeIf { it > 0f } ?: 0f

internal fun consumedAmount(previous: RawRecord, current: RawRecord): Float {
    val balanceDelta = current.totalBalance - previous.totalBalance
    return (topUpAmount(previous, current) + grantAmount(previous, current) - balanceDelta)
        .coerceAtLeast(0f)
}

private const val INFERRED_TOP_UP_EPSILON = 0.01f

/**
 * 判断浮点数是否接近整数（API 浮点漂移容差 0.01）。
 *
 * RecordAggregator、DailyEngine、IntradayEngine 共用。
 */
internal fun isNearInteger(value: Float): Boolean {
    val frac = value - value.toLong().toFloat()
    return frac < 0.01f || frac > 0.99f
}

/** 聚合分组键：(currency, accountId) */
data class AggregationKey(val currency: String, val accountId: String)
