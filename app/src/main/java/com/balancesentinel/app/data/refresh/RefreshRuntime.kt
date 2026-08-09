package com.balancesentinel.app.data.refresh

import android.content.Context
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
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
        val runRecorder = RoomRefreshRunRecorder(database)
        val ownerSessionId = UUID.randomUUID().toString()
        val source = AccountBalanceRefresher()
        val committer = RefreshResultCommitter(
            context = appContext,
            accountStore = accountStore,
            runRecorder = runRecorder
        )
        return RefreshCoordinator(
            accountStore = accountStore,
            source = source,
            committer = committer,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            runRecorder = runRecorder,
            ownerProcessSessionId = ownerSessionId,
            staleProjection = { accountId, failure ->
                val cached = BalanceWidgetDataStore.getAllBalances(appContext)
                    .filter { it.accountId == accountId }
                if (cached.isNotEmpty()) {
                    runCatching {
                        BalanceWidgetDataStore.markAccountStale(appContext, accountId, failure.message)
                    }
                }
                AccountRefreshResult.Failed(
                    accountId = accountId,
                    failure = failure,
                    stale = cached.isNotEmpty(),
                    dataTimestamp = cached.maxOfOrNull { it.lastUpdated },
                    lastError = failure.message
                )
            }
        )
    }
}
