package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.RawRecord

/**
 * IntradayEngine 的输入 — 24h 滑动窗口。
 */
data class IntradayInput(
    val rawRecords: List<RawRecord>,
    val filterCurrency: String,
    val filterAccountId: String?
)

/**
 * IntradayEngine 的输出。
 */
data class IntradayOutput(
    val trendPoints: List<IntradayPoint>,
    val billReport: IntradayBillReport,
    val dataPointCount: Int
)

/**
 * 24h 趋势图上单个数据点。
 */
data class IntradayPoint(
    val timestamp: Long,
    val actualBalance: Float,
    val isTopUp: Boolean,
    val isGrant: Boolean,
    val topUpAmount: Float,
    val grantAmount: Float
)

/**
 * 24h 账单汇总。
 */
data class IntradayBillReport(
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val netChange: Float
)
