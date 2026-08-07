package com.balancesentinel.app.data.refresh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class RefreshCoordinator(
    private val accountStore: RefreshAccountStore,
    private val source: AccountBalanceSource,
    private val committer: RefreshCommitter,
    private val backgroundScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) : RefreshGateway {
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val accountLocks = ConcurrentHashMap<String, Any>()

    override suspend fun refreshAccount(
        accountId: String,
        trigger: RefreshTrigger
    ): AccountRefreshResult {
        val token = nextToken(accountId)
        val account = when (val read = accountStore.readAccount(accountId)) {
            is AccountStoreRead.Ready -> read.accounts.firstOrNull()
                ?: return AccountRefreshResult.Skipped(accountId, "Account not found")
            AccountStoreRead.Missing -> return AccountRefreshResult.Skipped(accountId, "Account not found")
            is AccountStoreRead.Corrupt -> return AccountRefreshResult.Failed(accountId, RefreshFailure.AccountCorrupt("Account repository is corrupt"))
        }
        val request = RefreshRequest(
            accountId = account.id,
            revision = account.revision,
            token = token,
            trigger = trigger,
            startedAt = clock()
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

        val current = accountStore.readAccount(accountId)
        return synchronized(lockFor(accountId)) {
            if (!isLatest(accountId, token)) {
                return@synchronized stale(accountId)
            }
            if (current !is AccountStoreRead.Ready || current.accounts.firstOrNull()?.revision != request.revision) {
                return@synchronized stale(accountId)
            }
            when (fetched) {
                is BalanceFetchResult.Success -> committer.commit(request, fetched) {
                    isLatest(accountId, token)
                }
                is BalanceFetchResult.Failure -> AccountRefreshResult.Failed(
                    accountId,
                    fetched.failure
                )
            }
        }
    }

    override suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult> =
        supervisorScope {
        val accounts = when (val read = accountStore.readAccounts()) {
            is AccountStoreRead.Ready -> read.accounts
            AccountStoreRead.Missing, is AccountStoreRead.Corrupt -> emptyList()
        }
        accounts.map { account ->
                async { refreshAccount(account.id, trigger) }
            }.awaitAll()
        }


    override fun invalidate(accountId: String) {
        synchronized(lockFor(accountId)) {
            generations.computeIfAbsent(accountId) { AtomicLong(0) }.incrementAndGet()
        }
    }

    private fun nextToken(accountId: String): Long = synchronized(lockFor(accountId)) {
        generations.computeIfAbsent(accountId) { AtomicLong(0) }.incrementAndGet()
    }

    private fun isLatest(accountId: String, token: Long): Boolean =
        generations[accountId]?.get() == token

    private fun lockFor(accountId: String): Any =
        accountLocks.computeIfAbsent(accountId) { Any() }

    private fun stale(accountId: String) = AccountRefreshResult.Stale(
        accountId,
        RefreshFailure.AccountStale("Refresh result is stale")
    )
}
