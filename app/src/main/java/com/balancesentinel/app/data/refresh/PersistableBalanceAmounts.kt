package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.BalanceEntry

internal fun BalanceEntry.hasPersistableAmounts(): Boolean =
    totalBalance.isPersistableAsFloat() &&
        grantedBalance?.isPersistableAsFloat() != false &&
        toppedUpBalance?.isPersistableAsFloat() != false

private fun Double.isPersistableAsFloat(): Boolean =
    isFinite() && this >= -Float.MAX_VALUE.toDouble() && this <= Float.MAX_VALUE.toDouble()
