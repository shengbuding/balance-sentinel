package com.balancesentinel.app.data.local.publication

import com.balancesentinel.app.data.local.WalletDatabase

class MutationPublisher internal constructor(
    private val database: WalletDatabase,
    private val observer: TransactionStepObserver
) {
    constructor(database: WalletDatabase) : this(database, TransactionStepObserver.NO_OP)

    @Suppress("UNUSED_PARAMETER")
    suspend fun publish(input: MutationPublication): PublicationResult {
        throw UnsupportedOperationException("Room v1 publication is not implemented")
    }
}
