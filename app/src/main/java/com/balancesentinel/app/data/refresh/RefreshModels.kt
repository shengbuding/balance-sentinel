package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.ApiKeyManager

enum class RefreshTrigger { MANUAL_ALL, MANUAL_ACCOUNT, SERVICE, WIDGET, WATCHDOG }

sealed interface RefreshFailure {
    val message: String

    data class NetworkFailure(override val message: String) : RefreshFailure
    data class AuthenticationFailure(override val message: String) : RefreshFailure
    data class RateLimited(override val message: String) : RefreshFailure
    data class ResponseSchemaFailure(override val message: String) : RefreshFailure
    data class ScriptTimeout(override val message: String) : RefreshFailure
    data class ScriptPolicyDenied(override val message: String) : RefreshFailure
    data class AccountStale(override val message: String) : RefreshFailure
    data class PersistenceFailure(override val message: String) : RefreshFailure
}

data class RefreshRequest(
    val accountId: String,
    val revision: Long,
    val token: Long,
    val trigger: RefreshTrigger,
    val startedAt: Long
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
        val balance: UnifiedBalance
    ) : AccountRefreshResult

    data class Failed(
        override val accountId: String,
        val failure: RefreshFailure
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
    fun commit(
        request: RefreshRequest,
        fetched: BalanceFetchResult.Success,
        isLatest: () -> Boolean
    ): AccountRefreshResult
}

interface RefreshGateway {
    suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult
    suspend fun refreshAll(trigger: RefreshTrigger): List<AccountRefreshResult>
    fun invalidate(accountId: String)
}

interface RefreshAccountStore {
    fun getAccount(accountId: String): AccountInfo?
    fun getAccounts(): List<AccountInfo>
}

class ApiKeyRefreshAccountStore(
    private val apiKeyManager: ApiKeyManager
) : RefreshAccountStore {
    override fun getAccount(accountId: String): AccountInfo? = apiKeyManager.getAccount(accountId)
    override fun getAccounts(): List<AccountInfo> = apiKeyManager.getAccounts()
}
