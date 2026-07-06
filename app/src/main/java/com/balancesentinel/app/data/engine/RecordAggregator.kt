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
                val consumed = computeConsumed(sorted, toppedUp, granted)
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
                    grantedBalanceClose = sorted.last().grantedBalance
                )
            }
            .values
            .toList()
    }

    /** toppedUp = 累加每次 toppedUpBalance 的正向跳变 */
    fun computeToppedUp(sorted: List<RawRecord>): Float {
        var sum = 0f
        for (i in 1 until sorted.size) {
            val diff = sorted[i].toppedUpBalance - sorted[i - 1].toppedUpBalance
            if (diff > 0) sum += diff
        }
        return sum
    }

    /** granted = 累加每次 grantedBalance 的正向跳变 */
    fun computeGranted(sorted: List<RawRecord>): Float {
        var sum = 0f
        for (i in 1 until sorted.size) {
            val diff = sorted[i].grantedBalance - sorted[i - 1].grantedBalance
            if (diff > 0) sum += diff
        }
        return sum
    }

    /**
     * 消耗 = open - close + toppedUp + granted，下限 0。
     * 基于会计恒等式，无需猜测每笔 delta 方向，避免退款/过期误判。
     */
    fun computeConsumed(sorted: List<RawRecord>, toppedUp: Float, granted: Float): Float {
        val open = sorted.first().totalBalance
        val close = sorted.last().totalBalance
        return (open - close + toppedUp + granted).coerceAtLeast(0f)
    }
}

/** 聚合分组键：(currency, accountId) */
data class AggregationKey(val currency: String, val accountId: String)
