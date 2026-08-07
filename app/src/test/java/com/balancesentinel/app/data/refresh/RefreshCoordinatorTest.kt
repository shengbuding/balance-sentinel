package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.model.AccountInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCoordinatorTest {

    // Mutation caught: omitting the per-account generation check before an older completion commits.
    @Test
    fun `older completion is stale and only newest result commits`() = runTest {
        val first = CompletableDeferred<BalanceFetchResult>()
        val second = CompletableDeferred<BalanceFetchResult>()
        val source = QueueBalanceSource(first, second)
        val committer = RecordingCommitter()
        val coordinator = RefreshCoordinator(
            accountStore = MutableAccountStore(listOf(account())),
            source = source,
            committer = committer,
            backgroundScope = backgroundScope,
            clock = { 100L }
        )

        val old = async {
            coordinator.refreshAccount(ACCOUNT_ID, RefreshTrigger.MANUAL_ACCOUNT)
        }
        source.awaitStarted(0)
        val newest = async {
            coordinator.refreshAccount(ACCOUNT_ID, RefreshTrigger.WIDGET)
        }
        source.awaitStarted(1)
        second.complete(success(20.0))
        first.complete(success(10.0))

        assertTrue(newest.await() is AccountRefreshResult.Committed)
        assertTrue(old.await() is AccountRefreshResult.Stale)
        assertEquals(listOf(20.0), committer.committedBalances)
    }

    // Mutation caught: invalidate failing to advance the account generation during an in-flight fetch.
    @Test
    fun `invalidate makes an in flight result stale without committing`() = runTest {
        val fetched = CompletableDeferred<BalanceFetchResult>()
        val source = QueueBalanceSource(fetched)
        val committer = RecordingCommitter()
        val coordinator = RefreshCoordinator(
            MutableAccountStore(listOf(account())),
            source,
            committer,
            backgroundScope
        )

        val refresh = async {
            coordinator.refreshAccount(ACCOUNT_ID, RefreshTrigger.SERVICE)
        }
        source.awaitStarted(0)
        coordinator.invalidate(ACCOUNT_ID)
        fetched.complete(success(30.0))

        assertTrue(refresh.await() is AccountRefreshResult.Stale)
        assertTrue(committer.committedBalances.isEmpty())
    }

    // Mutation caught: one thrown account fetch cancelling successful siblings in refreshAll.
    @Test
    fun `refresh all isolates an account fetch exception`() = runTest {
        val accounts = listOf(account("first"), account("second"))
        val source = AccountBalanceSource { current ->
            if (current.id == "first") error("raw response with secret")
            success(8.0, current.id)
        }
        val committer = RecordingCommitter()
        val coordinator = RefreshCoordinator(
            MutableAccountStore(accounts),
            source,
            committer,
            backgroundScope
        )

        val results = coordinator.refreshAll(RefreshTrigger.MANUAL_ALL)

        assertTrue(results[0] is AccountRefreshResult.Failed)
        assertTrue(results[1] is AccountRefreshResult.Committed)
        assertEquals(listOf(8.0), committer.committedBalances)
        val failure = (results[0] as AccountRefreshResult.Failed).failure
        assertTrue(failure.message.length <= 96)
        assertTrue(!failure.message.contains("secret"))
    }

    @Test
    fun `revision change observed from repository makes in flight result stale`() = runTest {
        val fetched = CompletableDeferred<BalanceFetchResult>()
        val store = RevisionChangingStore(account())
        val committer = RecordingCommitter()
        val coordinator = RefreshCoordinator(store, AccountBalanceSource { fetched.await() }, committer, backgroundScope)

        val refresh = async { coordinator.refreshAccount(ACCOUNT_ID, RefreshTrigger.SERVICE) }
        store.changed = true
        fetched.complete(success(42.0))

        assertTrue(refresh.await() is AccountRefreshResult.Stale)
        assertTrue(committer.committedBalances.isEmpty())
    }

    private fun account(id: String = ACCOUNT_ID) = AccountInfo(
        id = id,
        label = id,
        apiKey = "api-key-$id-12345",
        providerType = ProviderType.DEEPSEEK,
        revision = 4
    )

    private fun success(amount: Double, accountId: String = ACCOUNT_ID) =
        BalanceFetchResult.Success(
            UnifiedBalance(
                provider = ProviderType.DEEPSEEK,
                accountId = accountId,
                isAvailable = true,
                balances = listOf(BalanceEntry("CNY", amount))
            ),
            completedAt = 200L
        )

    private class MutableAccountStore(
        private var accounts: List<AccountInfo>
    ) : RefreshAccountStore {
        override fun getAccount(accountId: String): AccountInfo? =
            accounts.find { it.id == accountId }

        override fun getAccounts(): List<AccountInfo> = accounts.toList()
    }

    private class RecordingCommitter : RefreshCommitter {
        val committedBalances = mutableListOf<Double>()

        override fun commit(
            request: RefreshRequest,
            fetched: BalanceFetchResult.Success,
            isLatest: () -> Boolean
        ): AccountRefreshResult {
            if (!isLatest()) {
                return AccountRefreshResult.Stale(
                    request.accountId,
                    RefreshFailure.AccountStale("Refresh result is stale")
                )
            }
            committedBalances += fetched.balance.balances.single().totalBalance
            return AccountRefreshResult.Committed(request.accountId, fetched.balance)
        }
    }

    private class QueueBalanceSource(
        vararg results: CompletableDeferred<BalanceFetchResult>
    ) : AccountBalanceSource {
        private val queue = results.toList()
        private val next = AtomicInteger()
        private val started = List(results.size) { CompletableDeferred<Unit>() }

        override suspend fun fetch(account: AccountInfo): BalanceFetchResult {
            val index = next.getAndIncrement()
            started[index].complete(Unit)
            return queue[index].await()
        }

        suspend fun awaitStarted(index: Int) = started[index].await()
    }

    private class RevisionChangingStore(private val initial: AccountInfo) : RefreshAccountStore {
        var changed = false
        override fun getAccount(accountId: String): AccountInfo? = initial
        override fun getAccounts(): List<AccountInfo> = listOf(initial)
        override suspend fun readAccount(accountId: String): AccountStoreRead =
            AccountStoreRead.Ready(listOf(if (changed) initial.copy(revision = initial.revision + 1) else initial))
    }

    private companion object {
        const val ACCOUNT_ID = "acct"
    }
}
