package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.DailySummary

data class HistoryAggregate(
    val accountId: String,
    val currency: String,
    val open: Float,
    val close: Float,
    val consumed: Float,
    val toppedUp: Float,
    val granted: Float,
    val avgBalance: Float,
    val sampleCount: Int,
    val toppedUpBalanceClose: Float,
    val grantedBalanceClose: Float
) {
    fun toDailySummary(date: String, generatedAt: Long): DailySummary = DailySummary(
        accountId = accountId,
        date = date,
        currency = currency,
        open = open,
        close = close,
        consumed = consumed,
        toppedUp = toppedUp,
        granted = granted,
        avgBalance = avgBalance,
        sampleCount = sampleCount,
        toppedUpBalanceClose = toppedUpBalanceClose,
        grantedBalanceClose = grantedBalanceClose,
        generatedAt = generatedAt
    )
}
