package com.balancesentinel.app.data.refresh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

class RefreshCoordinator(
    private val accountStore: RefreshAccountStore,
    private val source: AccountBalanceSource,
    private val committer: RefreshCommitter,
    private val backgroundScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) : RefreshGateway {
    override suspend fun refreshAccount(
        accountId: String,
        trigger: RefreshTrigger
    ): AccountRefreshResult {
        val account = accountStore.getAccount(accountId)
            ?: return AccountRefreshResult.Skipped(accountId, "Account not found")
        return try {
            backgroundScope.async { source.fetch(account) }.await()
            AccountRefreshResult.Skipped(accountId, "Refresh coordination is unavailable")
        } catch (_: Exception) {
            AccountRefreshResult.Failed(
                accountId,
                RefreshFailure.NetworkFailure("Balance request failed")
            )
        }
    }

    override suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult> =
        supervisorScope {
            accountStore.getAccounts().map { account ->
                async { refreshAccount(account.id, trigger) }
            }.awaitAll()
        }

    override fun invalidate(accountId: String) = Unit
}
