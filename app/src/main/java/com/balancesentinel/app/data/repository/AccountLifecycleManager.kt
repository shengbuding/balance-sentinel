package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountSaveResult
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import kotlinx.coroutines.runBlocking

class AccountLifecycleManager(
    private val context: Context,
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(context),
    private val gateway: RefreshGateway? =
        (context.applicationContext as? com.balancesentinel.app.DeepSeekApp)?.refreshGateway,
    private val injectedCoordinator: AccountMutationCoordinator? = null
) {
    /** Suspending entry point used by the Room-backed callers. */
    internal suspend fun saveAsync(
        existingId: String?,
        draft: AccountDraft
    ): AccountMutationResult = mutationCoordinator().save(existingId, draft)

    /** Suspending entry point used by the Room-backed callers. */
    internal suspend fun deleteAsync(accountId: String): AccountMutationResult =
        mutationCoordinator().delete(accountId)

    internal fun mutationCoordinator(): AccountMutationCoordinator =
        injectedCoordinator ?: RoomAccountMutationCoordinator(
            WalletDatabaseProvider.get(context),
            EncryptedPreferencesCredentialStore(context),
            mutationInvalidator = { gateway?.invalidate(it) }
        )

    fun save(existingId: String?, draft: AccountDraft): AccountSaveResult = runBlocking {
        val result = mutationCoordinator().save(existingId, draft)
        require(result is AccountMutationResult.Saved)
        result.result
    }

    fun delete(accountId: String) = runBlocking {
        val result = mutationCoordinator().delete(accountId)
        require(result is AccountMutationResult.Deleted)
        BalanceWidgetDataStore.removeAccountBalance(context, accountId)
        ApiDebugStore.clearEntries(accountId)
    }
}
