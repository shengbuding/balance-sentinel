package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.AiProvider
import com.balancesentinel.app.data.api.ProviderFactory
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo

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
        return when (val result = providerResolver.resolve(account).getBalance(account.toConfig())) {
            is ProviderResult.Success -> BalanceFetchResult.Success(result.data, completedAt = 0L)
            is ProviderResult.Failure -> BalanceFetchResult.Failure(
                RefreshFailure.ResponseSchemaFailure("Balance refresh is unavailable")
            )
        }
    }
}
