package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.AiProvider
import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderFeature
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.balance.BalanceQueryService

/** Native OpenCode Go balance provider. Its quota endpoint is a fixed, audited contract. */
class OpenCodeGoProvider(
    private val balanceQueryService: BalanceQueryService = BalanceQueryService()
) : AiProvider {
    override val providerType = ProviderType.OPENCODE_GO
    override val displayName = providerType.displayName
    override val supportedFeatures = setOf(ProviderFeature.BALANCE)

    override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> =
        balanceQueryService.queryBalance(config)

    override fun validateApiKeyFormat(apiKey: String): Boolean = apiKey.isNotBlank()
}
