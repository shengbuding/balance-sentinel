package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.AccountSaveResult

/** Result of a durable account mutation. */
sealed interface AccountMutationResult {
    data class Saved(val result: AccountSaveResult) : AccountMutationResult
    data class Deleted(val accountId: String) : AccountMutationResult
    data class Recovered(val operationIds: List<String>) : AccountMutationResult
}
