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

    fun save(existingId: String?, draft: AccountDraft): AccountSaveResult =
        DataMutationCoordinator.withMutation {
            apiKeyManager.saveAccountLegacy(existingId, draft) { result ->
                if (result is AccountSaveResult.Replaced) {
                    gateway?.invalidate(result.before.id)
                    val migration = mapOf(result.before.id to result.account.id)
                    RawRecordStore.migrateAccountIds(context, migration)
                    DailySummaryStore.migrateAccountIds(context, migration)
                    UsageDataStore.migrateAccountIds(context, migration)
                    BalanceWidgetDataStore.removeAccountBalance(context, result.before.id)
                    ProviderCache(context).clear(result.before.providerType, result.before.id)
                }
            }
        }

    fun delete(accountId: String) {
        DataMutationCoordinator.withMutation {
            apiKeyManager.removeAccount(accountId) { account ->
                gateway?.invalidate(accountId)
                RawRecordStore.removeByAccountId(context, accountId)
                DailySummaryStore.removeByAccountId(context, accountId)
                UsageDataStore.removeByAccountId(context, accountId)
                BalanceWidgetDataStore.removeAccountBalance(context, accountId)
                ProviderCache(context).clear(account.providerType, accountId)
                ApiDebugStore.clearEntries(accountId)
            }
        }
    }
}
