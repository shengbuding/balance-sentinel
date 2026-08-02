package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.balance.BalanceData
import com.balancesentinel.app.data.api.balance.ScriptExecutionException
import com.balancesentinel.app.data.api.balance.ScriptExecutionResult
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshFailure
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleProviderTest {

    @After
    fun tearDown() {
        unmockkAll()
        ShadowLog.clear()
    }

    @Test
    fun `custom script source is never written to the production log path`() = runTest {
        val scriptSource = "distinct-script-secret-7f3a9b"
        mockkObject(UsageScriptExecutor)
        coEvery {
            UsageScriptExecutor.execute(any<UsageScript>(), any<AccountInfo>())
        } returns ScriptExecutionResult.Success(
            balances = listOf(BalanceData(remaining = 1.0, unit = "USD", isValid = true))
        )
        ShadowLog.clear()

        val result = OpenAiCompatibleProvider(ProviderType.CUSTOM, "https://example.com")
            .getBalance(
                ProviderConfig(
                    providerType = ProviderType.CUSTOM,
                    credentials = mapOf("apiKey" to "test-api-key-12345"),
                    settings = mapOf("usageScript" to scriptSource)
                )
            )

        assertTrue(result is ProviderResult.Success)
        assertFalse(
            ShadowLog.getLogsForTag("OpenAiCompatibleProvider")
                .any { entry -> entry.msg.contains(scriptSource) }
        )
    }

    // Mutation caught: flattening a typed executor policy denial into a generic unavailable error.
    @Test
    fun `custom script policy failure remains typed for refresh mapping`() = runTest {
        val source =
            """({request:{url:"http://api.example.com/balance"},extractor:function(r){return r;}})"""

        val result = OpenAiCompatibleProvider(ProviderType.CUSTOM, "https://api.example.com")
            .getBalance(
                ProviderConfig(
                    providerType = ProviderType.CUSTOM,
                    credentials = mapOf(
                        "apiKey" to "test-api-key-12345",
                        "accountId" to "account-id"
                    ),
                    settings = mapOf(
                        "baseUrl" to "https://api.example.com",
                        "usageScript" to source,
                        "usageScriptEnabled" to "true"
                    )
                )
            )

        assertTrue(result is ProviderResult.Failure)
        val error = (result as ProviderResult.Failure).error
        assertTrue(error is ProviderError.InvalidResponseError)
        val carrier = error.cause as ScriptExecutionException
        assertTrue(carrier.failure is RefreshFailure.ScriptPolicyDenied)
    }
}
