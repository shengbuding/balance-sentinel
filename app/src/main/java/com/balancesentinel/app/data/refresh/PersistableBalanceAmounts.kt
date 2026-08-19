package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.BalanceEntry

internal fun BalanceEntry.hasPersistableAmounts(): Boolean =
    totalBalance.isPersistableAsFloat() &&
        grantedBalance?.isPersistableAsFloat() != false &&
        toppedUpBalance?.isPersistableAsFloat() != false &&
        quota?.periods?.all { period ->
            period.usedPercent.isFinite() &&
                period.remainingPercent.isFinite() &&
                period.usedPercent in 0.0..100.0 &&
                period.remainingPercent in 0.0..100.0
        } != false

private fun Double.isPersistableAsFloat(): Boolean =
    isFinite() && this >= -Float.MAX_VALUE.toDouble() && this <= Float.MAX_VALUE.toDouble()
