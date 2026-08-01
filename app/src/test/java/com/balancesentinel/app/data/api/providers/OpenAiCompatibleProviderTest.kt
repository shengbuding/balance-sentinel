package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.balance.BalanceData
import com.balancesentinel.app.data.api.balance.ScriptResult
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
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
            UsageScriptExecutor.execute(any(), any(), any(), any(), any(), any())
        } returns ScriptResult(
            success = true,
            data = listOf(BalanceData(remaining = 1.0, unit = "USD", isValid = true))
        )
        ShadowLog.clear()

        OpenAiCompatibleProvider(ProviderType.CUSTOM, "https://example.com")
            .getBalance(
                ProviderConfig(
                    providerType = ProviderType.CUSTOM,
                    credentials = mapOf("apiKey" to "test-api-key-12345"),
                    settings = mapOf("usageScript" to scriptSource)
                )
            )

        assertFalse(
            ShadowLog.getLogsForTag("OpenAiCompatibleProvider")
                .any { entry -> entry.msg.contains(scriptSource) }
        )
    }
}
