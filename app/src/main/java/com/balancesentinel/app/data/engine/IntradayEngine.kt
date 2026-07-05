package com.balancesentinel.app.data.engine

import com.balancesentinel.app.data.model.RawRecord

object IntradayEngine {

    fun compute(input: IntradayInput): IntradayOutput {
        val now = System.currentTimeMillis()
        val cutoff = now - 24 * 3600_000L

        val filtered = input.rawRecords
            .filter { it.currency == input.filterCurrency }
            .filter { input.filterAccountId == null || it.accountId == input.filterAccountId }
            .filter { it.timestamp >= cutoff }
            .sortedBy { it.timestamp }

        if (filtered.isEmpty()) {
            return IntradayOutput(emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0)
        }

        if (filtered.size == 1) {
            val r = filtered[0]
            return IntradayOutput(
                trendPoints = listOf(IntradayPoint(r.timestamp, r.totalBalance, false, false, 0f, 0f)),
                billReport = IntradayBillReport(0f, 0f, 0f, 0f),
                dataPointCount = 1
            )
        }

        val points = mutableListOf<IntradayPoint>()
        var totalConsumed = 0f
        var totalToppedUp = 0f
        var totalGranted = 0f

        points.add(IntradayPoint(filtered[0].timestamp, filtered[0].totalBalance, false, false, 0f, 0f))

        for (i in 1 until filtered.size) {
            val prev = filtered[i - 1]
            val curr = filtered[i]

            val balanceDelta = curr.totalBalance - prev.totalBalance
            val topUpDelta = curr.toppedUpBalance - prev.toppedUpBalance
            val grantDelta = curr.grantedBalance - prev.grantedBalance

            val isTopUp = topUpDelta >= 1f && isNearInteger(topUpDelta)
            val topUpAmount = if (isTopUp) topUpDelta else 0f
            val isGrant = grantDelta > 0f
            val grantAmount = if (isGrant) grantDelta else 0f

            val consumption = (topUpAmount + grantAmount - balanceDelta).coerceAtLeast(0f)

            totalConsumed += consumption
            totalToppedUp += topUpAmount
            totalGranted += grantAmount

            points.add(IntradayPoint(curr.timestamp, curr.totalBalance, isTopUp, isGrant, topUpAmount, grantAmount))
        }

        return IntradayOutput(
            trendPoints = points,
            billReport = IntradayBillReport(
                consumed = totalConsumed,
                toppedUp = totalToppedUp,
                granted = totalGranted,
                netChange = totalToppedUp + totalGranted - totalConsumed
            ),
            dataPointCount = points.size
        )
    }

    private fun isNearInteger(value: Float): Boolean {
        val frac = value - value.toLong().toFloat()
        return frac < 0.01f || frac > 0.99f
    }
}
