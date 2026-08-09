package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.model.AccountInfo

/** Room-backed run ledger. The RED seam intentionally fails until GREEN wiring lands. */
class RoomRefreshRunRecorder(
    private val database: WalletDatabase,
    private val beforeResultWrite: () -> Unit = {},
    private val ownerSessionFactory: () -> String = { "owner-session" }
) : RefreshRunRecorder {
    override suspend fun begin(
        trigger: RefreshTrigger,
        accounts: List<AccountInfo>,
        startedAt: Long,
        ownerProcessSessionId: String
    ): RefreshRunHandle = throw UnsupportedOperationException("RED: run recorder not wired")

    override suspend fun recordAccount(
        runId: String,
        request: RefreshRequest,
        result: AccountRefreshResult,
        persist: suspend () -> Unit
    ): AccountRefreshResult = throw UnsupportedOperationException("RED: run recorder not wired")

    override suspend fun finish(runId: String, completedAt: Long): RefreshBatchAggregate =
        throw UnsupportedOperationException("RED: run recorder not wired")

    override suspend fun recover(activeOwnerProcessSessionId: String, completedAt: Long): Int =
        throw UnsupportedOperationException("RED: run recorder not wired")
}
