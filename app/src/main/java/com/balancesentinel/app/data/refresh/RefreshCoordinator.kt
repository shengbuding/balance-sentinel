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
                val results = accounts.map { account ->
                    async {
                        try {
                            refreshAccountInternal(account.id, trigger, handle?.runId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            val failure = AccountRefreshResult.Failed(
                                account.id,
                                RefreshFailure.PersistenceFailure("Refresh account could not be completed")
                            )
                            runCatching {
                                recordTerminal(
                                    handle?.runId,
                                    requestFor(account.id, trigger, 0L, account.revision, handle?.runId),
                                    failure
                                )
                            }.getOrDefault(failure)
                        }
                    }
                }.awaitAll()
                val aggregate = runRecorder?.finish(runId, clock())
                    ?: deriveRefreshBatchAggregate(results)
                RefreshBatchResult(runId, results, aggregate)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        runRecorder?.cancelRunning(runId, clock())
                        runRecorder?.finish(runId, clock())
                    }
                }
                throw cancelled
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

            val current = accountStore.readAccount(accountId)
            return RefreshMutationBarrier.withRefreshCommitSuspend {
                accountLock(accountId).withLock {
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
                            if (runRecorder != null && !committer.recordsRunOutcome) {
                                recordTerminal(runId, request, committed)
                            } else {
                                committed
                            }
                        }
                        is BalanceFetchResult.Failure -> {
                            val projected = staleProjection(accountId, fetched.failure)
                            recordTerminal(runId, request, projected)
                        }
                    }
                }
            }
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
            throw cancelled
        }
    }

    private suspend fun recordTerminal(
        runId: String?,
        request: RefreshRequest,
        result: AccountRefreshResult
    ): AccountRefreshResult {
        if (runRecorder == null || runId == null) return result
        return RefreshMutationBarrier.withRefreshCommitSuspend {
            runRecorder.recordAccount(runId, request, result)
        }
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
