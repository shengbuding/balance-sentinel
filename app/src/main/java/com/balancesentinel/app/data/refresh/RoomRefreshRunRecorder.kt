package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.local.WalletDatabase
import androidx.room.withTransaction
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultEntity
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultState
import com.balancesentinel.app.data.local.refresh.RefreshErrorCategory
import com.balancesentinel.app.data.local.refresh.RefreshRunEntity
import com.balancesentinel.app.data.local.refresh.RefreshRunSource
import com.balancesentinel.app.data.local.refresh.RefreshRunState
import com.balancesentinel.app.data.model.AccountInfo
import java.util.UUID

/** Room-backed run ledger for per-account refresh outcomes and batch aggregates. */
class RoomRefreshRunRecorder(
    private val database: WalletDatabase,
    private val beforeResultWrite: () -> Unit = {},
    private val ownerSessionFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleProjection: suspend (String, RefreshFailure) -> AccountRefreshResult =
        { accountId, failure -> AccountRefreshResult.Failed(accountId, failure) }
) : RefreshRunRecorder {
    override suspend fun begin(
        trigger: RefreshTrigger,
        accounts: List<AccountInfo>,
        startedAt: Long,
        ownerProcessSessionId: String
    ): RefreshRunHandle {
        val runId = UUID.randomUUID().toString()
        database.withTransaction {
            database.refreshRunDao().insertRun(
                RefreshRunEntity(
                    id = runId,
                    source = trigger.toRunSource(),
                    ownerProcessSessionId = ownerProcessSessionId,
                    startedAt = startedAt,
                    accountCount = accounts.size
                )
            )
            accounts.forEach { account ->
                database.refreshRunDao().insertRunningResult(
                    RefreshAccountResultEntity(
                        runId = runId,
                        accountId = account.id,
                        accountRevision = account.revision,
                        startedAt = startedAt
                    )
                )
            }
        }
        return RefreshRunHandle(runId, ownerProcessSessionId)
    }

    override suspend fun recordAccount(
        runId: String,
        request: RefreshRequest,
        result: AccountRefreshResult,
        persist: suspend () -> Unit
    ): AccountRefreshResult {
        val terminal = result.toTerminal(request)
        try {
            writeTerminal(runId, request, terminal, persist, invokeHook = true)
            return result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The business-side transaction has rolled back. Persist a compensating
            // terminal outcome without invoking the injected result-side hook again,
            // so a failed refresh cannot strand its ledger row in RUNNING.
            val failure = RefreshFailure.PersistenceFailure("Refresh data could not be saved")
            val projected = try {
                staleProjection(request.accountId, failure)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AccountRefreshResult.Failed(request.accountId, failure)
            }
            runCatching {
                writeTerminal(
                    runId,
                    request,
                    projected.toTerminal(request),
                    persist = {},
                    invokeHook = false
                )
            }
            return projected
        }
    }

    private suspend fun writeTerminal(
        runId: String,
        request: RefreshRequest,
        terminal: Terminal,
        persist: suspend () -> Unit,
        invokeHook: Boolean
    ) {
        database.withTransaction {
            persist()
            if (invokeHook) beforeResultWrite()
            val updated = database.refreshRunDao().completeAccountAtomically(
                runId = runId,
                accountId = request.accountId,
                state = terminal.state,
                errorCategory = terminal.errorCategory,
                errorCode = terminal.errorCode,
                retryable = terminal.retryable,
                retryAfterAt = terminal.retryAfterAt,
                dataTimestamp = terminal.dataTimestamp,
                stale = terminal.stale,
                attemptCount = terminal.attemptCount,
                completedAt = terminal.completedAt
            )
            check(updated == 1) { "Refresh account result is no longer RUNNING" }
        }
    }

    override suspend fun finish(runId: String, completedAt: Long): RefreshBatchAggregate {
        database.refreshRunDao().deriveAndUpdateAggregate(runId, completedAt)
        val run = database.refreshRunDao().getRun(runId)
            ?: return RefreshBatchAggregate(RefreshBatchState.FAILED, 0, 0, 0, 0)
        return RefreshBatchAggregate(
            state = run.state.toBatchState(),
            accountCount = run.accountCount,
            successCount = run.successCount,
            failureCount = run.failureCount,
            cancelledCount = run.cancelledCount
        )
    }

    override suspend fun cancelRunning(runId: String, completedAt: Long): Int =
        database.withTransaction {
            database.refreshRunDao().cancelRunningResults(runId, completedAt)
        }

    override suspend fun recover(activeOwnerProcessSessionId: String, completedAt: Long): Int {
        return database.withTransaction {
            val interrupted = database.refreshRunDao()
                .interruptRunsWithoutOwner(activeOwnerProcessSessionId, completedAt)
            database.refreshRunDao().interruptRunningResults(completedAt)
            database.refreshRunDao().updateInterruptedCounts(completedAt)
            interrupted
        }
    }

    private data class Terminal(
        val state: RefreshAccountResultState,
        val errorCategory: RefreshErrorCategory?,
        val errorCode: String?,
        val retryable: Boolean,
        val retryAfterAt: Long?,
        val dataTimestamp: Long?,
        val stale: Boolean,
        val attemptCount: Int,
        val completedAt: Long
    )

    private fun AccountRefreshResult.toTerminal(request: RefreshRequest): Terminal = when (this) {
        is AccountRefreshResult.Committed -> Terminal(
            state = RefreshAccountResultState.SUCCEEDED,
            errorCategory = null,
            errorCode = null,
            retryable = false,
            retryAfterAt = null,
            dataTimestamp = dataTimestamp,
            stale = false,
            attemptCount = 1,
            completedAt = clock()
        )
        is AccountRefreshResult.Failed -> Terminal(
            state = failure.toResultState(),
            errorCategory = failure.toErrorCategory(),
            errorCode = failure.javaClass.simpleName,
            retryable = failure.retryable,
            retryAfterAt = failure.retryAfter,
            dataTimestamp = this.dataTimestamp,
            stale = stale,
            attemptCount = 1,
            completedAt = clock()
        )
        is AccountRefreshResult.Stale -> Terminal(
            state = RefreshAccountResultState.ACCOUNT_STALE,
            errorCategory = RefreshErrorCategory.ACCOUNT_STALE,
            errorCode = "ACCOUNT_STALE",
            retryable = false,
            retryAfterAt = null,
            dataTimestamp = null,
            stale = true,
            attemptCount = 1,
            completedAt = clock()
        )
        is AccountRefreshResult.Skipped -> Terminal(
            state = RefreshAccountResultState.SKIPPED,
            errorCategory = RefreshErrorCategory.UNKNOWN,
            errorCode = reason,
            retryable = false,
            retryAfterAt = null,
            dataTimestamp = null,
            stale = false,
            attemptCount = 1,
            completedAt = clock()
        )
    }

    private fun RefreshFailure.toResultState(): RefreshAccountResultState = when (this) {
        is RefreshFailure.AuthenticationFailure -> RefreshAccountResultState.AUTHENTICATION_FAILED
        is RefreshFailure.NetworkFailure -> RefreshAccountResultState.NETWORK_FAILED
        is RefreshFailure.RateLimited -> RefreshAccountResultState.RATE_LIMITED
        is RefreshFailure.ResponseSchemaFailure -> RefreshAccountResultState.RESPONSE_INVALID
        is RefreshFailure.ScriptPolicyDenied -> RefreshAccountResultState.SCRIPT_POLICY_DENIED
        is RefreshFailure.ScriptTimeout -> RefreshAccountResultState.SCRIPT_TIMEOUT
        is RefreshFailure.AccountStale -> RefreshAccountResultState.ACCOUNT_STALE
        is RefreshFailure.PersistenceFailure -> RefreshAccountResultState.PERSISTENCE_FAILED
        is RefreshFailure.AccountCorrupt -> RefreshAccountResultState.PERSISTENCE_FAILED
        is RefreshFailure.Cancelled -> RefreshAccountResultState.CANCELLED
    }

    private fun RefreshFailure.toErrorCategory(): RefreshErrorCategory = when (this) {
        is RefreshFailure.AuthenticationFailure -> RefreshErrorCategory.AUTHENTICATION
        is RefreshFailure.NetworkFailure -> RefreshErrorCategory.NETWORK
        is RefreshFailure.RateLimited -> RefreshErrorCategory.RATE_LIMIT
        is RefreshFailure.ResponseSchemaFailure -> RefreshErrorCategory.RESPONSE
        is RefreshFailure.ScriptPolicyDenied -> RefreshErrorCategory.SCRIPT_POLICY
        is RefreshFailure.ScriptTimeout -> RefreshErrorCategory.SCRIPT_TIMEOUT
        is RefreshFailure.AccountStale -> RefreshErrorCategory.ACCOUNT_STALE
        is RefreshFailure.PersistenceFailure -> RefreshErrorCategory.PERSISTENCE
        is RefreshFailure.AccountCorrupt -> RefreshErrorCategory.UNKNOWN
        is RefreshFailure.Cancelled -> RefreshErrorCategory.CANCELLED
    }

    private fun RefreshTrigger.toRunSource(): RefreshRunSource = when (this) {
        RefreshTrigger.MANUAL_ALL, RefreshTrigger.MANUAL_ACCOUNT -> RefreshRunSource.MANUAL
        RefreshTrigger.SERVICE -> RefreshRunSource.FOREGROUND
        RefreshTrigger.WIDGET, RefreshTrigger.WATCHDOG -> RefreshRunSource.WIDGET
    }

    private fun RefreshRunState.toBatchState(): RefreshBatchState = when (this) {
        RefreshRunState.SUCCEEDED -> RefreshBatchState.SUCCEEDED
        RefreshRunState.PARTIAL -> RefreshBatchState.PARTIAL
        RefreshRunState.CANCELLED -> RefreshBatchState.CANCELLED
        RefreshRunState.FAILED, RefreshRunState.INTERRUPTED, RefreshRunState.RUNNING -> RefreshBatchState.FAILED
    }
}
