package com.balancesentinel.app.data.repository

sealed interface StoreWriteResult {
    data class Written(val itemCount: Int) : StoreWriteResult

    data class Failed(
        val operation: String,
        val reason: String
    ) : StoreWriteResult
}
