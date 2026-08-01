package com.balancesentinel.app.data.refresh

import kotlinx.coroutines.CoroutineScope

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
    ): AccountRefreshResult = AccountRefreshResult.Skipped(
        accountId = accountId,
        reason = "Refresh coordination is unavailable"
    )

    override suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult> =
        accountStore.getAccounts().map { account ->
            AccountRefreshResult.Skipped(account.id, "Refresh coordination is unavailable")
        }

    override fun invalidate(accountId: String) = Unit
}
