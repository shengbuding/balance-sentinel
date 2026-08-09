package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.repository.AccountRepository
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountUiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class RefreshTrigger { MANUAL_ALL, MANUAL_ACCOUNT, SERVICE, WIDGET, WATCHDOG }

sealed interface RefreshFailure {
    val message: String
    /** Whether a subsequent refresh may retry this failure automatically. */
    val retryable: Boolean
        get() = false
    /** Provider-suggested retry delay (milliseconds or provider-native value). */
    val retryAfter: Long?
        get() = null

    data class NetworkFailure(
        override val message: String,
        override val retryable: Boolean = true,
        override val retryAfter: Long? = null,
        val cause: Throwable? = null
    ) : RefreshFailure
    data class AuthenticationFailure(override val message: String) : RefreshFailure
    data class RateLimited(
        override val message: String,
        override val retryable: Boolean = true,
        override val retryAfter: Long? = null
    ) : RefreshFailure
    data class ResponseSchemaFailure(override val message: String) : RefreshFailure
    data class ScriptTimeout(override val message: String) : RefreshFailure
    data class ScriptPolicyDenied(override val message: String) : RefreshFailure
    data class AccountStale(override val message: String) : RefreshFailure
    data class PersistenceFailure(override val message: String) : RefreshFailure
    data class AccountCorrupt(override val message: String) : RefreshFailure
    data class Cancelled(override val message: String = "Refresh cancelled") : RefreshFailure
}

data class RefreshRequest(
    val accountId: String,
    val revision: Long,
    val token: Long,
    val trigger: RefreshTrigger,
    val startedAt: Long,
    val runId: String? = null
)

sealed interface BalanceFetchResult {
    data class Success(
        val balance: UnifiedBalance,
        val completedAt: Long
    ) : BalanceFetchResult

    data class Failure(val failure: RefreshFailure) : BalanceFetchResult
}

sealed interface AccountRefreshResult {
    val accountId: String

    data class Committed(
        override val accountId: String,
        val balance: UnifiedBalance,
        val dataTimestamp: Long? = null
    ) : AccountRefreshResult

    data class Failed(
        override val accountId: String,
        val failure: RefreshFailure,
        val stale: Boolean = false,
        val dataTimestamp: Long? = null,
        val lastError: String? = null
    ) : AccountRefreshResult

    data class Stale(
        override val accountId: String,
        val failure: RefreshFailure.AccountStale
    ) : AccountRefreshResult

    data class Skipped(
        override val accountId: String,
        val reason: String
    ) : AccountRefreshResult

}

fun interface AccountBalanceSource {
    suspend fun fetch(account: AccountInfo): BalanceFetchResult
}

interface RefreshCommitter {
    val recordsRunOutcome: Boolean
        get() = false

    suspend fun commit(
        request: RefreshRequest,
        fetched: BalanceFetchResult.Success,
        isLatest: () -> Boolean
    ): AccountRefreshResult
}

interface RefreshGateway {
    suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult
    suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult
    fun invalidate(accountId: String)
    suspend fun readAccountSnapshot(): AccountStoreRead = AccountStoreRead.Missing
}

interface RefreshAccountStore {
    fun getAccount(accountId: String): AccountInfo?
    fun getAccounts(): List<AccountInfo>

    suspend fun readAccount(accountId: String): AccountStoreRead =
        getAccount(accountId)?.let { AccountStoreRead.Ready(listOf(it)) } ?: AccountStoreRead.Missing

    suspend fun readAccounts(): AccountStoreRead = AccountStoreRead.Ready(getAccounts())
}

sealed interface AccountStoreRead {
    data class Ready(val accounts: List<AccountInfo>) : AccountStoreRead
    data object Missing : AccountStoreRead
    data class Corrupt(val error: DataCorruptionException) : AccountStoreRead
}

class ApiKeyRefreshAccountStore(
    private val apiKeyManager: ApiKeyManager
) : RefreshAccountStore {
    override fun getAccount(accountId: String): AccountInfo? = apiKeyManager.getAccount(accountId)
    override fun getAccounts(): List<AccountInfo> = apiKeyManager.getAccounts()
}

/** Room metadata plus encrypted credential payload, loaded off the caller thread. */
class RoomRefreshAccountStore(
    private val accountRepository: AccountRepository,
    private val accountUiRepository: AccountUiRepository
) : RefreshAccountStore {
    @Volatile private var snapshot: List<AccountInfo> = emptyList()

    override fun getAccount(accountId: String): AccountInfo? = snapshot.firstOrNull { it.id == accountId }
    override fun getAccounts(): List<AccountInfo> = snapshot

    override suspend fun readAccount(accountId: String): AccountStoreRead =
        when (val result = readAccounts()) {
            is AccountStoreRead.Ready -> result.accounts.firstOrNull { it.id == accountId }
                ?.let { AccountStoreRead.Ready(listOf(it)) } ?: AccountStoreRead.Missing
            is AccountStoreRead.Corrupt -> result
            AccountStoreRead.Missing -> AccountStoreRead.Missing
        }

    override suspend fun readAccounts(): AccountStoreRead = withContext(Dispatchers.IO) {
        when (val state = accountUiRepository.observe().first { it !is AccountLoadState.Loading }) {
            is AccountLoadState.Ready -> {
                snapshot = state.accounts
                AccountStoreRead.Ready(state.accounts)
            }
            is AccountLoadState.Corrupt -> AccountStoreRead.Corrupt(state.error)
            AccountLoadState.Loading -> error("unreachable")
        }
    }
}
