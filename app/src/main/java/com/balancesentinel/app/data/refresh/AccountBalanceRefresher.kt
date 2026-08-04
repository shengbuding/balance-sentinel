package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.AiProvider
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderFactory
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.balance.ScriptExecutionException
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.CancellationException

fun interface BalanceProviderResolver {
    fun resolve(account: AccountInfo): AiProvider
}

class AccountBalanceRefresher(
    private val providerResolver: BalanceProviderResolver = BalanceProviderResolver { account ->
        if (account.providerType == ProviderType.CUSTOM) {
            ProviderFactory.get(account.providerType, account.extraSettings["baseUrl"])
        } else {
            ProviderFactory.get(account.providerType)
        }
    },
    private val clock: () -> Long = System::currentTimeMillis
) : AccountBalanceSource {

    override suspend fun fetch(account: AccountInfo): BalanceFetchResult {
        if (!account.usageScript.isNullOrBlank() && !account.usageScriptEnabled) {
            return failure(
                RefreshFailure.ScriptPolicyDenied("Custom balance script is disabled")
            )
        }

        return try {
            val provider = providerResolver.resolve(account)
            when (val result = provider.getBalance(account.toConfig())) {
                is ProviderResult.Success -> validate(account, result.data)
                is ProviderResult.Failure -> failure(mapProviderError(result.error))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            failure(
                RefreshFailure.ResponseSchemaFailure("Provider configuration is invalid")
            )
        } catch (_: Exception) {
            failure(RefreshFailure.NetworkFailure("Balance request failed"))
        }
    }

    private fun validate(account: AccountInfo, balance: UnifiedBalance): BalanceFetchResult {
        val invalid = balance.accountId != account.id ||
            balance.provider != account.providerType ||
            balance.balances.isEmpty() ||
            balance.balances.any { entry ->
                entry.currency.isBlank() ||
                    !entry.hasPersistableAmounts()
            }
        return if (invalid) {
            failure(
                RefreshFailure.ResponseSchemaFailure("Balance response schema is invalid")
            )
        } else {
            BalanceFetchResult.Success(balance, completedAt = clock())
        }
    }

    private fun mapProviderError(error: ProviderError): RefreshFailure = when (error) {
        is ProviderError.AuthError ->
            RefreshFailure.AuthenticationFailure("Authentication failed")
        is ProviderError.RateLimitError ->
            RefreshFailure.RateLimited("Provider rate limit reached")
        is ProviderError.NetworkError ->
            RefreshFailure.NetworkFailure("Network request failed")
        is ProviderError.ServerError ->
            RefreshFailure.NetworkFailure("Provider service failed (HTTP ${error.code})")
        is ProviderError.QuotaExceededError ->
            RefreshFailure.RateLimited("Provider quota is exhausted")
        is ProviderError.InvalidResponseError ->
            (error.cause as? ScriptExecutionException)?.failure
                ?: RefreshFailure.ResponseSchemaFailure("Balance response schema is invalid")
        is ProviderError.ApiUnavailableError ->
            RefreshFailure.ResponseSchemaFailure("Balance is unavailable for this provider")
    }

    private fun failure(failure: RefreshFailure) = BalanceFetchResult.Failure(failure)
}
