package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.model.AccountInfo

data class RefreshRunHandle(
    val runId: String,
    val ownerProcessSessionId: String
)

interface RefreshRunRecorder {
    suspend fun begin(
        trigger: RefreshTrigger,
        accounts: List<AccountInfo>,
        startedAt: Long,
        ownerProcessSessionId: String
    ): RefreshRunHandle

    suspend fun recordAccount(
        runId: String,
        request: RefreshRequest,
        result: AccountRefreshResult,
        persist: suspend () -> Unit = {}
    ): AccountRefreshResult

    suspend fun finish(runId: String, completedAt: Long): RefreshBatchAggregate

    /** Mark any rows that did not reach a terminal outcome before cancellation. */
    suspend fun cancelRunning(runId: String, completedAt: Long): Int = 0

    suspend fun recover(activeOwnerProcessSessionId: String, completedAt: Long): Int
}
