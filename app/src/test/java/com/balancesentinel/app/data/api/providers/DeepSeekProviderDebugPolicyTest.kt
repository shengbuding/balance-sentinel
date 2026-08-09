package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.network.NetworkResponseException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekProviderDebugPolicyTest {
    // Mutation caught: ignoring the explicit policy on the provider-owned usage client.
    @Test
    fun `provider usage client installs one debug interceptor and zero in release`() {
        val debug = DeepSeekProvider(debuggable = true).getClientWithDebug("acct")
        val release = DeepSeekProvider(debuggable = false).getClientWithDebug("acct")

        assertEquals(1, debug.interceptors.count { it is DebugInterceptor })
        assertEquals(0, release.interceptors.count { it is DebugInterceptor })
    }

    @Test
    fun `provider usage retains bounded non success status and body metadata`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody("provider-response-secret")
            )
            val provider = DeepSeekProvider()
            val clientField = DeepSeekProvider::class.java.getDeclaredField("client")
            clientField.isAccessible = true
            clientField.set(provider, OkHttpClient())

            val result = provider.getUsage(
                ProviderConfig(
                    providerType = ProviderType.DEEPSEEK,
                    credentials = mapOf("apiKey" to "test-api-key"),
                    settings = mapOf("baseUrl" to server.url("/").toString().trimEnd('/'))
                )
            )

            assertTrue(result is ProviderResult.Failure)
            val error = (result as ProviderResult.Failure).error
            assertTrue(error is ProviderError.RateLimitError)
            assertEquals(429, (error.cause as NetworkResponseException).statusCode)
            assertEquals("provider-response-secret", (error.cause as NetworkResponseException).limitedBody)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 8_000)
    fun `cancelling provider usage cancels the underlying call and preserves the cancellation`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = AtomicBoolean(false)
            val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .eventListenerFactory(EventListener.Factory {
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            cancelled.set(true)
                        }
                    }
                })
                .build()
            val provider = DeepSeekProvider()
            val clientField = DeepSeekProvider::class.java.getDeclaredField("client")
            clientField.isAccessible = true
            clientField.set(provider, client)
            var observed: Throwable? = null
            val job = launch(Dispatchers.IO) {
                try {
                    provider.getUsage(
                        ProviderConfig(
                            providerType = ProviderType.DEEPSEEK,
                            credentials = mapOf("apiKey" to "test-api-key"),
                            settings = mapOf("baseUrl" to server.url("/").toString().trimEnd('/'))
                        )
                    )
                } catch (failure: Throwable) {
                    observed = failure
                    throw failure
                }
            }

            checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val cancellation = CancellationException("provider cancellation")
            job.cancel(cancellation)
            job.join()

            assertTrue(cancelled.get())
            assertTrue(observed === cancellation)
        } finally {
            server.shutdown()
        }
    }
}
