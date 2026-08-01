package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.providers.OpenAiCompatibleProvider
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@RunWith(RobolectricTestRunner::class)
class BalanceQueryServiceTest {

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
    fun `unsupported provider does not probe generic endpoints`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            repeat(5) {
                server.enqueue(MockResponse().setResponseCode(404))
            }
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
            server.enqueue(MockResponse().setBody(resource("balance/stepfun.json")))
            val service = newService(OkHttpClient(), endpointOverride(server))

            val result = query(
                service,
                config(ProviderType.CUSTOM, "https://api.stepfun.com/v1")
            )

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
            val service = newService(OkHttpClient(), endpointOverride(server))
            val config = config(ProviderType.DEEPSEEK, "https://api.deepseek.com")

            val unauthorized = query(service, config) as ProviderResult.Failure
            val limited = query(service, config) as ProviderResult.Failure
            val unavailable = query(service, config) as ProviderResult.Failure

            assertTrue(unauthorized.error is ProviderError.AuthError)
            assertTrue(limited.error is ProviderError.RateLimitError)
            assertTrue(unavailable.error is ProviderError.ServerError)
            assertFalse(unauthorized.error.message.contains("api-key-secret"))
            assertFalse(limited.error.message.contains("token-secret"))
            assertFalse(unavailable.error.message.contains("raw-response-secret"))
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun query(
        service: Any,
        config: ProviderConfig
    ): ProviderResult<UnifiedBalance> = suspendCoroutine { continuation ->
        val query = service.javaClass.methods.single { method ->
            method.name == "queryBalance" && method.parameterCount == 2
        }
        try {
            val returned = query.invoke(service, config, continuation)
            if (returned !== COROUTINE_SUSPENDED) {
                continuation.resume(returned as ProviderResult<UnifiedBalance>)
            }
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        }
    }

    private fun newService(
        callFactory: Call.Factory,
        endpointOverride: (Any) -> HttpUrl
    ): Any {
        val type = try {
            Class.forName("com.balancesentinel.app.data.api.balance.BalanceQueryService")
        } catch (error: ClassNotFoundException) {
            fail("strict balance query service is not implemented")
            throw AssertionError(error)
        }
        val constructor = type.declaredConstructors.singleOrNull { candidate ->
            candidate.parameterCount == 2 &&
                Call.Factory::class.java.isAssignableFrom(candidate.parameterTypes[0])
        } ?: run {
            fail("strict balance query service constructor is not implemented")
            throw AssertionError()
        }
        return constructor.newInstance(callFactory, endpointOverride)
    }

    private fun endpointOverride(server: MockWebServer): (Any) -> HttpUrl = { contract ->
        val endpoint = contract.javaClass.methods
            .single { method -> method.name == "getEndpoint" && method.parameterCount == 0 }
            .invoke(contract) as HttpUrl
        server.url(endpoint.encodedPath)
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
