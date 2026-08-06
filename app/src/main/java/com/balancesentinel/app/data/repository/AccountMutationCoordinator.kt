package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.AccountDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for account mutations. Implementations must publish a
 * complete Room state before reporting success.
 */
interface AccountMutationCoordinator {
    suspend fun save(existingId: String?, draft: AccountDraft): AccountMutationResult

    suspend fun delete(accountId: String): AccountMutationResult
}

/**
 * Compatibility adapter for callers that still use the legacy JSON manager.
 * It deliberately preserves the old behavior until the Room-backed
 * implementation is installed by the lifecycle task.
 */
class LegacyAccountMutationCoordinatorAdapter(
    private val lifecycleManager: AccountLifecycleManager
) : AccountMutationCoordinator {
    override suspend fun save(
        existingId: String?,
        draft: AccountDraft
    ): AccountMutationResult = withContext(Dispatchers.IO) {
        AccountMutationResult.Saved(lifecycleManager.save(existingId, draft))
    }

    override suspend fun delete(accountId: String): AccountMutationResult = withContext(Dispatchers.IO) {
        lifecycleManager.delete(accountId)
        AccountMutationResult.Deleted(accountId)
    }
}
