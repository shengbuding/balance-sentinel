package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.providers.OpenAiCompatibleProvider
import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.network.NetworkResponseException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceQueryServiceTest {

    // Mutation caught: ignoring an explicit Release policy in strict/shared balance construction.
    @Test
    fun `strict balance client installs one debug interceptor and zero in release`() {
        val config = config(ProviderType.DEEPSEEK, "https://api.deepseek.com")
        val contract = checkNotNull(BuiltInBalanceContracts.resolve(ProviderType.DEEPSEEK, config.baseUrl))
        val debug = BalanceQueryService(OkHttpClient(), debuggable = true)
            .callFactoryFor(config, contract) as OkHttpClient
        val release = BalanceQueryService(OkHttpClient(), debuggable = false)
            .callFactoryFor(config, contract) as OkHttpClient

        assertEquals(1, debug.interceptors.count { it is DebugInterceptor })
        assertEquals(0, release.interceptors.count { it is DebugInterceptor })
    }

    // Mutation caught: adding a second capture interceptor to an already instrumented shared client.
    @Test
    fun `strict balance never duplicates an existing capture interceptor`() {
        val config = config(ProviderType.DEEPSEEK, "https://api.deepseek.com")
        val contract = checkNotNull(BuiltInBalanceContracts.resolve(ProviderType.DEEPSEEK, config.baseUrl))
        val base = OkHttpClient.Builder().addInterceptor(DebugInterceptor("shared")).build()

        val client = BalanceQueryService(base, debuggable = true)
            .callFactoryFor(config, contract) as OkHttpClient

        assertEquals(1, client.interceptors.count { it is DebugInterceptor })
    }

    @Test
    fun `server errors never retain raw response bodies`() {
        val error = ProviderError.ServerError(
            ProviderType.DEEPSEEK,
            503,
            "raw-response-secret"
        )

        assertEquals(503, error.code)
        assertFalse(error.message.contains("raw-response-secret"))
    }

    @Test
    fun `network errors expose a stable message and retain no exception details`() {
        val error = ProviderError.NetworkError(
            ProviderType.DEEPSEEK,
            IOException("network-error-secret")
        )

        assertEquals("网络错误", error.message)
        assertFalse(error.message.contains("network-error-secret"))
        assertNull(error.cause)
    }

    @Test
    fun `configured script failure never falls through to a native contract`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(resource("balance/stepfun.json"))
            )
            val service = BalanceQueryService(OkHttpClient(), endpointOverride(server))
            val provider = OpenAiCompatibleProvider(
                ProviderType.CUSTOM,
                "https://api.stepfun.com",
                service
            )
            val result = provider.getBalance(
                ProviderConfig(
                    providerType = ProviderType.CUSTOM,
                    credentials = mapOf("apiKey" to "test-api-key-12345"),
                    settings = mapOf(
                        "baseUrl" to "https://api.stepfun.com",
                        "usageScript" to "this is not valid JavaScript {"
                    )
                )
            )

            assertTrue(result is ProviderResult.Failure)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unsupported provider does not probe generic endpoints`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            repeat(5) { server.enqueue(MockResponse().setResponseCode(404)) }
            val baseUrl = server.url("/").toString()
            val result = OpenAiCompatibleProvider(ProviderType.MOONSHOT, baseUrl)
                .getBalance(config(ProviderType.MOONSHOT, baseUrl))

            assertTrue(result is ProviderResult.Failure)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolved contract sends exactly one request to its declared path`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(resource("balance/stepfun.json"))
            )
            val service = BalanceQueryService(OkHttpClient(), endpointOverride(server))
            val result = service.queryBalance(config(ProviderType.CUSTOM, "https://api.stepfun.com/v1"))

            assertTrue(result is ProviderResult.Success)
            assertEquals(1, server.requestCount)
            assertEquals("/v1/accounts", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http failures are typed and never contain response bodies`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("api-key-secret"))
            server.enqueue(MockResponse().setResponseCode(429).setBody("token-secret"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("raw-response-secret"))
            val service = BalanceQueryService(OkHttpClient(), endpointOverride(server))
            val config = config(ProviderType.DEEPSEEK, "https://api.deepseek.com")

            val unauthorized = service.queryBalance(config) as ProviderResult.Failure
            val limited = service.queryBalance(config) as ProviderResult.Failure
            val unavailable = service.queryBalance(config) as ProviderResult.Failure

            assertTrue(unauthorized.error is ProviderError.AuthError)
            assertTrue(limited.error is ProviderError.RateLimitError)
            assertTrue(unavailable.error is ProviderError.ServerError)
            assertFalse(unauthorized.error.message.contains("api-key-secret"))
            assertFalse(limited.error.message.contains("token-secret"))
            assertFalse(unavailable.error.message.contains("raw-response-secret"))
            assertEquals(401, (unauthorized.error.cause as NetworkResponseException).statusCode)
            assertEquals("api-key-secret", (unauthorized.error.cause as NetworkResponseException).limitedBody)
            assertEquals(429, (limited.error.cause as NetworkResponseException).statusCode)
            assertEquals("token-secret", (limited.error.cause as NetworkResponseException).limitedBody)
            assertEquals(503, (unavailable.error.cause as NetworkResponseException).statusCode)
            assertEquals("raw-response-secret", (unavailable.error.cause as NetworkResponseException).limitedBody)
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `non OkHttp factories fail closed instead of bypassing the encoded budget`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(resource("balance/stepfun.json"))
            )
            val delegate = OkHttpClient()
            val factory = object : Call.Factory {
                override fun newCall(request: Request): Call = delegate.newCall(request)
            }
            val service = BalanceQueryService(factory, endpointOverride(server))
            val config = config(ProviderType.CUSTOM, "https://api.stepfun.com/v1")

            val result = service.queryBalance(config)

            assertTrue(result is ProviderResult.Failure)
            assertEquals(0, server.requestCount)
            val contract = checkNotNull(
                BuiltInBalanceContracts.resolve(ProviderType.CUSTOM, config.baseUrl)
            )
            assertThrows(IllegalArgumentException::class.java) {
                service.callFactoryFor(config, contract)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 8_000)
    fun `cancelling balance query cancels the underlying call and preserves the cancellation`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = AtomicBoolean(false)
            val client = OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .eventListenerFactory(EventListener.Factory {
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            cancelled.set(true)
                        }
                    }
                })
                .build()
            val service = BalanceQueryService(client, endpointOverride(server))
            val config = config(ProviderType.DEEPSEEK, "https://api.deepseek.com")
            var observed: Throwable? = null
            val job = launch(Dispatchers.IO) {
                try {
                    service.queryBalance(config)
                } catch (failure: Throwable) {
                    observed = failure
                    throw failure
                }
            }

            checkNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            val cancellation = CancellationException("balance cancellation")
            job.cancel(cancellation)
            job.join()

            assertTrue(cancelled.get())
            assertTrue(observed === cancellation)
        } finally {
            server.shutdown()
        }
    }

    private fun endpointOverride(server: MockWebServer): (BalanceContract) -> HttpUrl = { contract ->
        server.url(contract.endpoint.encodedPath)
    }

    private fun config(providerType: ProviderType, baseUrl: String): ProviderConfig =
        ProviderConfig(
            providerType = providerType,
            credentials = mapOf(
                "apiKey" to "test-api-key-12345",
                "accountId" to "acct"
            ),
            settings = mapOf("baseUrl" to baseUrl)
        )

    private fun resource(path: String): String {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) { "missing fixture: $path" }
        return url.readText()
    }
}
