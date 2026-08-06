package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

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

/**
 * Room-backed UI account source. Room owns the observable metadata and the
 * encrypted payload supplies credential-bearing fields needed by editors.
 */
class RoomAccountUiRepository(
    private val accountRepository: AccountRepository,
    private val credentialStore: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AccountUiRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<AccountLoadState> = accountRepository.observeVerified()
        .mapLatest { rows ->
            withContext(ioDispatcher) { reconcile(rows) }
        }
        .onStart { emit(AccountLoadState.Loading) }
        .catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            emit(AccountLoadState.Corrupt(asCorruption(error)))
        }

    private fun reconcile(rows: List<RepositoryAccount>): AccountLoadState {
        return when (val result = credentialStore.read()) {
            CredentialReadResult.Missing -> {
                if (rows.isEmpty()) {
                    AccountLoadState.Ready(emptyList())
                } else {
                    AccountLoadState.Corrupt(
                        DataCorruptionException("Account metadata has no credential payload")
                    )
                }
            }
            is CredentialReadResult.Corrupt -> AccountLoadState.Corrupt(result.exception)
            is CredentialReadResult.Valid -> try {
                result.payload.validate()
                val used = mutableSetOf<Int>()
                val accounts = rows.sortedBy { it.displayOrder }.map { row ->
                    val index = result.payload.accounts.indexOfFirst { account ->
                        account.id == row.id || account.id == row.legacyStorageId
                    }
                    require(index >= 0) { "Credential payload has no account ${row.id}" }
                    require(used.add(index)) { "Credential payload maps more than once to ${row.id}" }
                    val source = result.payload.accounts[index]
                    require(source.providerType == row.providerType) {
                        "Account provider does not match Room metadata for ${row.id}"
                    }
                    require(source.revision == row.revision) {
                        "Account revision does not match Room metadata for ${row.id}"
                    }
                    source.copy(
                        id = row.id,
                        label = row.label,
                        providerType = row.providerType,
                        revision = row.revision
                    )
                }
                require(used.size == result.payload.accounts.size) {
                    "Credential payload contains accounts not published in Room"
                }
                AccountLoadState.Ready(accounts)
            } catch (error: Exception) {
                AccountLoadState.Corrupt(asCorruption(error))
            }
        }
    }

    private fun asCorruption(error: Throwable): DataCorruptionException =
        error as? DataCorruptionException
            ?: DataCorruptionException("Account repository state cannot be read", error)
}
