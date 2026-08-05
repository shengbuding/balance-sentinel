package com.balancesentinel.app.data.local.publication

internal enum class TransactionStep {
    AFTER_ACCOUNT_ROWS,
    AFTER_SETTINGS_ROWS,
    AFTER_METADATA,
    AFTER_OPERATION_PUBLISHED
}

internal fun interface TransactionStepObserver {
    fun after(step: TransactionStep)

    companion object {
        val NO_OP = TransactionStepObserver { }
    }
}
