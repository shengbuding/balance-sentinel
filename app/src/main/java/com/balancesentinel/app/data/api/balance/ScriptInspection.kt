package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.refresh.RefreshFailure

sealed interface ScriptExecutionResult {
    data class Success(val balances: List<BalanceData>) : ScriptExecutionResult
    data class Failure(val failure: RefreshFailure) : ScriptExecutionResult
}

data class ScriptInspection(
    val request: RequestConfig?,
    val requiredExtraOrigins: Set<WebOrigin>,
    val staticallyDeterminable: Boolean,
    val failure: RefreshFailure? = null
)
