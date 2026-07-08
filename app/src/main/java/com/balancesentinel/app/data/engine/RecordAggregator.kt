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
     * toppedUp = 累加每次 toppedUpBalance 的正向跳变。
     * 仅计入 >=1 且接近整数的跳变（与 IntradayEngine/DailyEngine 守卫一致），
     * 避免 API 浮点漂移被误判为充值。
     */
    fun computeToppedUp(sorted: List<RawRecord>): Float {
        var sum = 0f
        for (i in 1 until sorted.size) {
            val diff = sorted[i].toppedUpBalance - sorted[i - 1].toppedUpBalance
            if (diff >= 1f && isNearInteger(diff)) sum += diff
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

    private fun isNearInteger(value: Float): Boolean {
        val frac = value - value.toLong().toFloat()
        return frac < 0.01f || frac > 0.99f
    }

    /**
     * 消耗 = 逐对累加 totalBalance 纯下降量。
     *
     * 跳过 toppedUpBalance 或 grantedBalance 发生变化的区间（该区间由充值/赠送解释），
     * 只计入余额自然下降的幅度。不依赖充值和赠送值做会计公式反推。
     */
    fun computeConsumed(sorted: List<RawRecord>): Float {
        var consumed = 0f
        for (i in 1 until sorted.size) {
            val balanceDelta = sorted[i].totalBalance - sorted[i - 1].totalBalance
            val topDelta = sorted[i].toppedUpBalance - sorted[i - 1].toppedUpBalance
            val grantDelta = sorted[i].grantedBalance - sorted[i - 1].grantedBalance

            // 充值/赠送区间跳过，剩余余额下降视为纯消费
            if (topDelta >= 1f && isNearInteger(topDelta)) continue
            if (grantDelta > 0f) continue
            if (balanceDelta < 0f) consumed += -balanceDelta
        }
        return consumed
    }
}

/** 聚合分组键：(currency, accountId) */
data class AggregationKey(val currency: String, val accountId: String)
