package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Typed account state consumed by UI-facing repositories. */
sealed interface AccountLoadState {
    data object Loading : AccountLoadState
    data class Ready(val accounts: List<AccountInfo>) : AccountLoadState
    data class Corrupt(val error: DataCorruptionException) : AccountLoadState
}

fun interface AccountUiRepository {
    fun observe(): Flow<AccountLoadState>
}

/**
 * Compatibility adapter for callers that have not moved to the Room-backed
 * account source yet. It preserves the legacy read while making corruption
 * explicit to new consumers.
 */
class LegacyAccountUiRepository(
    private val apiKeyManager: ApiKeyManager
) : AccountUiRepository {
    override fun observe(): Flow<AccountLoadState> = flow {
        emit(AccountLoadState.Loading)
        emit(
            try {
                AccountLoadState.Ready(apiKeyManager.getAccounts())
            } catch (error: DataCorruptionException) {
                AccountLoadState.Corrupt(error)
            }
        )
    }
}
