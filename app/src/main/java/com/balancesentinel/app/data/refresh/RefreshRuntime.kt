package com.balancesentinel.app.data.refresh

import android.content.Context
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

object RefreshRuntime {
    /**
     * Obtain the Application-scoped gateway. Production callers use this;
     * tests inject their own [RefreshGateway] directly.
     */
    fun from(context: Context): RefreshGateway =
        (context.applicationContext as com.balancesentinel.app.DeepSeekApp).refreshGateway

    fun create(context: Context): RefreshGateway {
        val appContext = context.applicationContext
        val accountRepository = RoomAccountRepository(WalletDatabaseProvider.get(appContext))
        val accountStore = RoomRefreshAccountStore(
            accountRepository,
            RoomAccountUiRepository(accountRepository, EncryptedPreferencesCredentialStore(appContext))
        )
        val database = WalletDatabaseProvider.get(appContext)
        val ownerSessionId = UUID.randomUUID().toString()
        val source = AccountBalanceRefresher()
        val staleProjection: suspend (String, RefreshFailure) -> AccountRefreshResult = { accountId, failure ->
            val cached = BalanceWidgetDataStore.getAllBalances(appContext)
                .filter { it.accountId == accountId }
            projectStaleFailure(accountId, failure, cached) {
                BalanceWidgetDataStore.markAccountStale(appContext, accountId, failure.message)
            }
        }
        val runRecorder = RoomRefreshRunRecorder(
            database = database,
            staleProjection = staleProjection
        )
        val committer = RefreshResultCommitter(
            context = appContext,
            accountStore = accountStore,
            runRecorder = runRecorder,
            staleProjection = staleProjection
        )
        return RefreshCoordinator(
            accountStore = accountStore,
            source = source,
            committer = committer,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            runRecorder = runRecorder,
            ownerProcessSessionId = ownerSessionId,
            staleProjection = staleProjection
        )
    }

    internal fun projectStaleFailure(
        accountId: String,
        failure: RefreshFailure,
        cached: List<AccountBalance>,
        markStale: () -> Unit
    ): AccountRefreshResult.Failed {
        if (cached.isEmpty()) {
            return AccountRefreshResult.Failed(
                accountId = accountId,
                failure = failure,
                stale = false,
                dataTimestamp = null,
                lastError = failure.message
            )
        }

        val persisted = try {
            markStale()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return AccountRefreshResult.Failed(
            accountId = accountId,
            failure = failure,
            stale = persisted,
            dataTimestamp = cached.maxOfOrNull { it.lastUpdated }.takeIf { persisted },
            lastError = failure.message
        )
    }
}
