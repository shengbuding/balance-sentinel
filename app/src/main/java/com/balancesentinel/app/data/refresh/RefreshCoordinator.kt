package com.balancesentinel.app.data.refresh

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RefreshCoordinator(
    private val accountStore: RefreshAccountStore,
    private val source: AccountBalanceSource,
    private val committer: RefreshCommitter,
    private val backgroundScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runRecorder: RefreshRunRecorder? = null,
    private val ownerProcessSessionId: String = UUID.randomUUID().toString(),
    private val staleProjection: suspend (String, RefreshFailure) -> AccountRefreshResult =
        { accountId, failure -> AccountRefreshResult.Failed(accountId, failure) }
) : RefreshGateway {
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun refreshAccount(
        accountId: String,
        trigger: RefreshTrigger
    ): AccountRefreshResult {
        val accounts = when (val read = accountStore.readAccount(accountId)) {
            is AccountStoreRead.Ready -> read.accounts
            AccountStoreRead.Missing -> emptyList()
            is AccountStoreRead.Corrupt -> emptyList()
        }
        val handle = runRecorder?.begin(trigger, accounts, clock(), ownerProcessSessionId)
        val result = try {
            refreshAccountInternal(accountId, trigger, handle?.runId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    handle?.runId?.let { runId ->
                        runRecorder?.cancelRunning(runId, clock())
                        runRecorder?.finish(runId, clock())
                    }
                }
            }
            throw cancelled
        }
        if (handle != null) {
            runRecorder.finish(handle.runId, clock())
        }
        return result
    }

    override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult =
        supervisorScope {
            val accounts = when (val read = accountStore.readAccounts()) {
                is AccountStoreRead.Ready -> read.accounts
                AccountStoreRead.Missing, is AccountStoreRead.Corrupt -> emptyList()
            }
            runRecorder?.recover(ownerProcessSessionId, clock())
            val handle = runRecorder?.begin(trigger, accounts, clock(), ownerProcessSessionId)
            val runId = handle?.runId ?: UUID.randomUUID().toString()
            try {
                val tasks = accounts.map { account ->
                    async {
                        try {
                            refreshAccountInternal(account.id, trigger, handle?.runId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            val failure = RefreshFailure.PersistenceFailure("Refresh account could not be completed")
                            val projected = try {
                                staleProjection(account.id, failure)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AccountRefreshResult.Failed(account.id, failure)
                            }
                            try {
                                recordTerminal(
                                    handle?.runId,
                                    requestFor(account.id, trigger, 0L, account.revision, handle?.runId),
                                    projected
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                projected
                            }
                        }
                    }
                }
                RefreshDiagnostics.record(
                    stage = RefreshDiagnosticStage.RUN_AWAIT_STARTED,
                    runId = runId,
                    trigger = trigger,
                    timestamp = clock(),
                    detail = "accounts=${accounts.size}"
                )
                val results = tasks.awaitAll()
                RefreshDiagnostics.record(
                    stage = RefreshDiagnosticStage.RUN_AWAIT_COMPLETED,
                    runId = runId,
                    trigger = trigger,
                    timestamp = clock(),
                    detail = "results=${results.size}"
                )
                val aggregate = runRecorder?.finish(runId, clock())
                    ?: deriveRefreshBatchAggregate(results)
                if (runRecorder == null) {
                    RefreshDiagnostics.record(
                        stage = RefreshDiagnosticStage.RUN_FINISHED,
                        runId = runId,
                        trigger = trigger,
                        timestamp = clock(),
                        detail = "state=${aggregate.state}",
                        terminal = true
                    )
                }
                RefreshBatchResult(runId, results, aggregate)
            } catch (cancelled: CancellationException) {
                RefreshDiagnostics.record(
                    stage = RefreshDiagnosticStage.RUN_CANCELLED,
                    runId = runId,
                    trigger = trigger,
                    timestamp = clock(),
                    detail = cancelled.javaClass.simpleName
                )
                withContext(NonCancellable) {
                    runCatching {
                        runRecorder?.cancelRunning(runId, clock())
                        runRecorder?.finish(runId, clock())
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                RefreshDiagnostics.record(
                    stage = RefreshDiagnosticStage.RUN_FAILED,
                    runId = runId,
                    trigger = trigger,
                    timestamp = clock(),
                    detail = error.javaClass.simpleName,
                    terminal = true
                )
                throw error
            }
        }

    override fun invalidate(accountId: String) {
        synchronized(generationLock(accountId)) {
            generations.computeIfAbsent(accountId) { AtomicLong(0) }.incrementAndGet()
        }
    }

    override suspend fun readAccountSnapshot(): AccountStoreRead = accountStore.readAccounts()

    private suspend fun refreshAccountInternal(
        accountId: String,
        trigger: RefreshTrigger,
        runId: String?
    ): AccountRefreshResult {
        var activeRequest: RefreshRequest? = null
        try {
            val token = nextToken(accountId)
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.ACCOUNT_STARTED,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = token,
                timestamp = clock()
            )
            RefreshMutationBarrier.register(accountId) { invalidate(accountId) }
            val account = when (val read = accountStore.readAccount(accountId)) {
                is AccountStoreRead.Ready -> read.accounts.firstOrNull()
                AccountStoreRead.Missing -> null
                is AccountStoreRead.Corrupt -> {
                    val result = AccountRefreshResult.Failed(
                        accountId,
                        RefreshFailure.AccountCorrupt("Account repository is corrupt")
                    )
                    return recordTerminal(runId, requestFor(accountId, trigger, token, 0L), result)
                }
            }
            if (account == null) {
                val result = AccountRefreshResult.Skipped(accountId, "Account not found")
                return recordTerminal(runId, requestFor(accountId, trigger, token, 0L), result)
            }
            val request = requestFor(account.id, trigger, token, account.revision, runId)
            activeRequest = request
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.FETCH_STARTED,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = token,
                timestamp = clock()
            )
            val fetched = try {
                withContext(backgroundScope.coroutineContext.minusKey(Job)) {
                    source.fetch(account)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BalanceFetchResult.Failure(
                    RefreshFailure.NetworkFailure("Balance request failed")
                )
            }
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.FETCH_RETURNED,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = token,
                timestamp = clock(),
                detail = when (fetched) {
                    is BalanceFetchResult.Success -> "SUCCESS"
                    is BalanceFetchResult.Failure -> fetched.failure.javaClass.simpleName
                }
            )

            val current = accountStore.readAccount(accountId)
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.COMMIT_BARRIER_WAIT,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = token,
                timestamp = clock()
            )
            val result = RefreshMutationBarrier.withRefreshCommitSuspend {
                RefreshDiagnostics.record(
                    stage = RefreshDiagnosticStage.COMMIT_BARRIER_ENTERED,
                    runId = runId,
                    accountId = accountId,
                    trigger = trigger,
                    generation = token,
                    timestamp = clock()
                )
                RefreshDiagnostics.record(
                    stage = RefreshDiagnosticStage.ACCOUNT_LOCK_WAIT,
                    runId = runId,
                    accountId = accountId,
                    trigger = trigger,
                    generation = token,
                    timestamp = clock()
                )
                accountLock(accountId).withLock {
                    RefreshDiagnostics.record(
                        stage = RefreshDiagnosticStage.ACCOUNT_LOCK_ENTERED,
                        runId = runId,
                        accountId = accountId,
                        trigger = trigger,
                        generation = token,
                        timestamp = clock()
                    )
                    if (!isLatest(accountId, token)) {
                        return@withLock recordTerminal(runId, request, stale(accountId))
                    }
                    if (current !is AccountStoreRead.Ready ||
                        current.accounts.firstOrNull()?.revision != request.revision
                    ) {
                        return@withLock recordTerminal(runId, request, stale(accountId))
                    }
                    when (fetched) {
                        is BalanceFetchResult.Success -> {
                            val committed = committer.commit(request, fetched) { isLatest(accountId, token) }
                            val projected = if (
                                committed is AccountRefreshResult.Failed &&
                                !committed.stale &&
                                !committer.recordsRunOutcome
                            ) {
                                staleProjection(accountId, committed.failure)
                            } else {
                                committed
                            }
                            if (runRecorder != null && !committer.recordsRunOutcome) {
                                recordTerminal(runId, request, projected)
                            } else {
                                projected
                            }
                        }
                        is BalanceFetchResult.Failure -> {
                            val projected = staleProjection(accountId, fetched.failure)
                            recordTerminal(runId, request, projected)
                        }
                    }
                }
            }
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.ACCOUNT_COMPLETED,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = token,
                timestamp = clock(),
                detail = result.javaClass.simpleName,
                terminal = true
            )
            return result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                activeRequest?.let { request ->
                    runCatching {
                        recordTerminal(
                            runId,
                            request,
                            AccountRefreshResult.Failed(
                                request.accountId,
                                RefreshFailure.Cancelled()
                            )
                        )
                    }
                }
            }
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.ACCOUNT_CANCELLED,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = activeRequest?.token,
                timestamp = clock(),
                detail = cancelled.javaClass.simpleName,
                terminal = true
            )
            throw cancelled
        } catch (error: Exception) {
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.ACCOUNT_FAILED,
                runId = runId,
                accountId = accountId,
                trigger = trigger,
                generation = activeRequest?.token,
                timestamp = clock(),
                detail = error.javaClass.simpleName,
                terminal = true
            )
            throw error
        }
    }

    private suspend fun recordTerminal(
        runId: String?,
        request: RefreshRequest,
        result: AccountRefreshResult
    ): AccountRefreshResult {
        if (runRecorder == null || runId == null) return result
        RefreshDiagnostics.record(
            stage = RefreshDiagnosticStage.ACCOUNT_TERMINAL_WRITE_STARTED,
            runId = runId,
            accountId = request.accountId,
            trigger = request.trigger,
            generation = request.token,
            timestamp = clock(),
            detail = result.javaClass.simpleName
        )
        val recorded = RefreshMutationBarrier.withRefreshCommitSuspend {
            runRecorder.recordAccount(runId, request, result)
        }
        RefreshDiagnostics.record(
            stage = RefreshDiagnosticStage.ACCOUNT_TERMINAL_RECORDED,
            runId = runId,
            accountId = request.accountId,
            trigger = request.trigger,
            generation = request.token,
            timestamp = clock(),
            detail = recorded.javaClass.simpleName,
            terminal = true
        )
        return recorded
    }

    private fun requestFor(
        accountId: String,
        trigger: RefreshTrigger,
        token: Long,
        revision: Long,
        runId: String? = null
    ) = RefreshRequest(accountId, revision, token, trigger, clock(), runId)

    private fun nextToken(accountId: String): Long = synchronized(generationLock(accountId)) {
        generations.computeIfAbsent(accountId) { AtomicLong(0) }.incrementAndGet()
    }

    private fun isLatest(accountId: String, token: Long): Boolean =
        generations[accountId]?.get() == token

    private fun generationLock(accountId: String): Any =
        accountLocks.computeIfAbsent(accountId) { Mutex() }

    private fun accountLock(accountId: String): Mutex =
        accountLocks.computeIfAbsent(accountId) { Mutex() }

    private fun stale(accountId: String) = AccountRefreshResult.Stale(
        accountId,
        RefreshFailure.AccountStale("Refresh result is stale")
    )
}
