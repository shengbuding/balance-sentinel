package com.balancesentinel.app.data.refresh

import com.balancesentinel.app.data.api.AiProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.balance.ScriptExecutionException
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBalanceRefresherTest {

    // Mutation caught: performing a second provider request or stamping before the response returns.
    @Test
    fun `successful fetch uses one provider request and stamps response completion`() = runTest {
        val provider = RecordingProvider(
            ProviderResult.Success(balance(12.5))
        )
        val refresher = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider },
            clock = { 4_200L }
        )

        val result = refresher.fetch(account()) as BalanceFetchResult.Success

        assertEquals(1, provider.balanceCalls)
        assertEquals(12.5, result.balance.balances.single().totalBalance, 0.0)
        assertEquals(4_200L, result.completedAt)
        assertEquals(ACCOUNT_ID, provider.configs.single().credentials["accountId"])
    }

    // Mutation caught: dropping the configured script before invoking the existing provider path.
    @Test
    fun `custom script configuration reaches the selected provider unchanged`() = runTest {
        val provider = RecordingProvider(ProviderResult.Success(balance(7.0, ProviderType.CUSTOM)))
        val scripted = account(
            providerType = ProviderType.CUSTOM,
            usageScript = "return balance",
            extraSettings = mapOf("baseUrl" to "https://api.example.com")
        )
        val refresher = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider },
            clock = { 9L }
        )

        val result = refresher.fetch(scripted) as BalanceFetchResult.Success

        assertEquals(7.0, result.balance.balances.single().totalBalance, 0.0)
        assertEquals("return balance", provider.configs.single().settings["usageScript"])
        assertEquals("https://api.example.com", provider.configs.single().baseUrl)
    }

    // Mutation caught: executing a script whose persisted policy flag is disabled.
    @Test
    fun `disabled custom script is denied before provider execution`() = runTest {
        val provider = RecordingProvider(ProviderResult.Success(balance(1.0, ProviderType.CUSTOM)))
        val refresher = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider }
        )

        val result = refresher.fetch(
            account(
                providerType = ProviderType.CUSTOM,
                usageScript = "return balance",
                usageScriptEnabled = false,
                extraSettings = mapOf("baseUrl" to "https://api.example.com")
            )
        )

        assertTrue(result is BalanceFetchResult.Failure)
        assertTrue((result as BalanceFetchResult.Failure).failure is RefreshFailure.ScriptPolicyDenied)
        assertEquals(0, provider.balanceCalls)
    }

    // Mutation caught: retaining a raw provider message that contains credentials or response data.
    @Test
    fun `provider failures map to bounded summaries without raw diagnostic data`() = runTest {
        val secret = "Cookie=wallet-secret-token raw-response-body"
        val provider = RecordingProvider(
            ProviderResult.Failure(ProviderError.AuthError(ProviderType.DEEPSEEK, secret))
        )
        val refresher = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider }
        )

        val result = refresher.fetch(account()) as BalanceFetchResult.Failure

        assertTrue(result.failure is RefreshFailure.AuthenticationFailure)
        assertTrue(result.failure.message.length <= 96)
        assertFalse(result.failure.message.contains("Cookie", ignoreCase = true))
        assertFalse(result.failure.message.contains("wallet-secret-token"))
        assertFalse(result.failure.message.contains("raw-response-body"))
    }

    // Mutation caught: flattening a typed provider script failure into a response schema failure.
    @Test
    fun `typed script failure survives the provider boundary`() = runTest {
        val provider = RecordingProvider(
            ProviderResult.Failure(
                ProviderError.InvalidResponseError(
                    provider = ProviderType.CUSTOM,
                    message = "Script extractor timed out",
                    cause = ScriptExecutionException(
                        RefreshFailure.ScriptTimeout("Script extractor timed out")
                    )
                )
            )
        )
        val refresher = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider }
        )

        val result = refresher.fetch(
            account(
                providerType = ProviderType.CUSTOM,
                usageScript = "configured script",
                extraSettings = mapOf("baseUrl" to "https://api.example.com")
            )
        ) as BalanceFetchResult.Failure

        assertTrue(result.failure is RefreshFailure.ScriptTimeout)
    }

    // Mutation caught: committing NaN or infinity as a valid monetary balance.
    @Test
    fun `non finite provider amount is a response schema failure`() = runTest {
        val provider = RecordingProvider(ProviderResult.Success(balance(Double.NaN)))
        val refresher = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider }
        )

        val result = refresher.fetch(account())

        assertTrue(result is BalanceFetchResult.Failure)
        assertTrue((result as BalanceFetchResult.Failure).failure is RefreshFailure.ResponseSchemaFailure)
    }

    // Mutation caught: accepting a finite Double that overflows persisted Float total balance.
    @Test
    fun `out of Float range total is a response schema failure`() = runTest {
        assertOutOfFloatRangeRejected(BalanceEntry("CNY", OUT_OF_FLOAT_RANGE))
    }

    // Mutation caught: accepting a finite Double that overflows persisted Float granted balance.
    @Test
    fun `out of Float range granted amount is a response schema failure`() = runTest {
        assertOutOfFloatRangeRejected(
            BalanceEntry("CNY", 1.0, grantedBalance = OUT_OF_FLOAT_RANGE)
        )
    }

    // Mutation caught: accepting a finite Double that overflows persisted Float topped-up balance.
    @Test
    fun `out of Float range topped up amount is a response schema failure`() = runTest {
        assertOutOfFloatRangeRejected(
            BalanceEntry("CNY", 1.0, toppedUpBalance = OUT_OF_FLOAT_RANGE)
        )
    }

    private suspend fun assertOutOfFloatRangeRejected(entry: BalanceEntry) {
        val provider = RecordingProvider(ProviderResult.Success(balance(entry)))
        val result = AccountBalanceRefresher(
            providerResolver = BalanceProviderResolver { provider }
        ).fetch(account())

        assertTrue(result is BalanceFetchResult.Failure)
        assertTrue((result as BalanceFetchResult.Failure).failure is RefreshFailure.ResponseSchemaFailure)
    }

    private fun account(
        providerType: ProviderType = ProviderType.DEEPSEEK,
        usageScript: String? = null,
        usageScriptEnabled: Boolean = true,
        extraSettings: Map<String, String> = emptyMap()
    ) = AccountInfo(
        id = ACCOUNT_ID,
        label = "Primary",
        apiKey = "api-key-123456",
        providerType = providerType,
        usageScript = usageScript,
        usageScriptEnabled = usageScriptEnabled,
        extraSettings = extraSettings,
        revision = 3
    )

    private fun balance(
        amount: Double,
        providerType: ProviderType = ProviderType.DEEPSEEK
    ) = UnifiedBalance(
        provider = providerType,
        accountId = ACCOUNT_ID,
        isAvailable = true,
        balances = listOf(BalanceEntry("CNY", amount))
    )

    private fun balance(entry: BalanceEntry) = UnifiedBalance(
        provider = ProviderType.DEEPSEEK,
        accountId = ACCOUNT_ID,
        isAvailable = true,
        balances = listOf(entry)
    )

    private class RecordingProvider(
        private val result: ProviderResult<UnifiedBalance>
    ) : AiProvider {
        override val providerType = ProviderType.DEEPSEEK
        override val displayName = "Recording"
        val configs = mutableListOf<ProviderConfig>()
        var balanceCalls = 0

        override suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance> {
            balanceCalls++
            configs += config
            return result
        }
    }

    private companion object {
        const val ACCOUNT_ID = "acct"
        val OUT_OF_FLOAT_RANGE = Float.MAX_VALUE.toDouble() * 2
    }
}
