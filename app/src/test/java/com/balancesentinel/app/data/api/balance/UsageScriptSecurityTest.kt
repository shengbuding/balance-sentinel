package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.network.NetworkResponseException
import com.balancesentinel.app.data.refresh.RefreshFailure
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class UsageScriptSecurityTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        ApiDebugStore.clearAll()
        server.shutdown()
    }

    // Mutation caught: omitting custom-script diagnostics, duplicating capture, or retaining source text.
    @Test
    fun `custom script emits exactly one source free diagnostic only when enabled`() = runBlocking {
        val source = """({request:{url:"https://api.example.com/balance"},extractor:function(r){return r;}})"""
        val script = UsageScript(source, timeout = 1)
        server.enqueue(successResponse())

        val debugResult = UsageScriptExecutor.execute(
            script = script,
            account = account("https://api.example.com"),
            resolver = PUBLIC_RESOLVER,
            client = client,
            connectionUrlOverride = ::routeToTestServer,
            debuggable = true
        )

        assertSuccess(debugResult)
        val entries = ApiDebugStore.getEntries("account-id")
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertTrue(entry.isCustomScript)
        assertEquals(source.length, entry.scriptCharacterCount)
        assertTrue(entry.scriptSha256?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(!entry.toString().contains(source))

        ApiDebugStore.clearAll()
        server.enqueue(successResponse())
        val releaseResult = UsageScriptExecutor.execute(
            script = script,
            account = account("https://api.example.com"),
            resolver = PUBLIC_RESOLVER,
            client = client,
            connectionUrlOverride = ::routeToTestServer,
            debuggable = false
        )

        assertSuccess(releaseResult)
        assertTrue(ApiDebugStore.getEntries("account-id").isEmpty())
    }

    // Mutation caught: denying the full same origin, including its registered non-default port.
    @Test
    fun `same origin https request succeeds`() = runBlocking {
        server.enqueue(successResponse())

        val result = execute(
            requestUrl = "https://api.example.com:8443/balance",
            baseUrl = "https://api.example.com:8443/v1"
        )

        assertSuccess(result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `non success script responses retain bounded status and body metadata`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("script-service-secret")
        )

        val result = execute(
            requestUrl = "https://api.example.com/balance",
            baseUrl = "https://api.example.com/v1"
        )

        assertTrue(result is ScriptExecutionResult.Failure)
        val failure = (result as ScriptExecutionResult.Failure).failure
        assertTrue(failure is RefreshFailure.NetworkFailure)
        val networkFailureCause = failure.javaClass.getDeclaredField("cause").let { field ->
            field.isAccessible = true
            field.get(failure) as NetworkResponseException
        }
        assertEquals(503, networkFailureCause.statusCode)
        assertEquals("script-service-secret", networkFailureCause.limitedBody)
    }

    @Test(timeout = 8_000)
    fun `cancelling script request cancels the underlying call and preserves the cancellation`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cancelled = AtomicBoolean(false)
        val cancellableClient = client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .eventListenerFactory(EventListener.Factory {
                object : EventListener() {
                    override fun canceled(call: Call) {
                        cancelled.set(true)
                    }
                }
            })
            .build()
        val script = UsageScript(
            """({request:{url:"https://api.example.com/balance"},extractor:function(r){return r;}})""",
            timeout = 30
        )
        var observed: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                UsageScriptExecutor.execute(
                    script = script,
                    account = account("https://api.example.com"),
                    resolver = PUBLIC_RESOLVER,
                    client = cancellableClient,
                    connectionUrlOverride = ::routeToTestServer
                )
            } catch (failure: Throwable) {
                observed = failure
                throw failure
            }
        }

        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val cancellation = CancellationException("script cancellation")
        job.cancel(cancellation)
        job.join()

        assertTrue(cancelled.get())
        assertTrue(observed === cancellation)
    }

    // Mutation caught: ignoring an explicitly authorized canonical public origin.
    @Test
    fun `authorized public origin succeeds`() = runBlocking {
        server.enqueue(successResponse())

        val result = execute(
            requestUrl = "https://cdn.example.com/balance",
            baseUrl = "https://api.example.com/v1",
            authorizedOrigins = setOf("https://cdn.example.com")
        )

        assertSuccess(result)
        assertEquals(1, server.requestCount)
    }

    // Mutation caught: performing a request before rejecting cleartext HTTP.
    @Test
    fun `http request is denied before connection`() = runBlocking {
        val result = execute(
            requestUrl = "http://api.example.com/balance",
            baseUrl = "https://api.example.com/v1"
        )

        assertPolicyDenied(result)
        assertEquals(0, server.requestCount)
    }

    // Mutation caught: treating an unlisted cross-origin destination as same origin.
    @Test
    fun `unauthorized origin is denied before connection`() = runBlocking {
        val result = execute(
            requestUrl = "https://cdn.example.com/balance",
            baseUrl = "https://api.example.com/v1"
        )

        assertPolicyDenied(result)
        assertEquals(0, server.requestCount)
    }

    // Mutation caught: connecting after policy DNS resolves the destination to a private address.
    @Test
    fun `private dns result is denied before connection`() = runBlocking {
        val result = execute(
            requestUrl = "https://api.example.com/balance",
            baseUrl = "https://api.example.com/v1",
            resolver = FixedResolver(mapOf("api.example.com" to listOf("10.0.0.7")))
        )

        assertPolicyDenied(result)
        assertEquals(0, server.requestCount)
    }

    // Mutation caught: allowing OkHttp automatic redirects or skipping policy on a redirect target.
    @Test
    fun `cross origin redirect is denied without a second request`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://evil.example.com/stolen")
        )

        val result = execute(
            requestUrl = "https://api.example.com/start",
            baseUrl = "https://api.example.com/v1"
        )

        assertPolicyDenied(result)
        assertEquals(1, server.requestCount)
    }

    // Mutation caught: following more than five manually revalidated redirects.
    @Test
    fun `redirect chain follows at most five redirects`() = runBlocking {
        repeat(6) { index ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/redirect-${index + 1}")
            )
        }

        val result = execute(
            requestUrl = "https://api.example.com/start",
            baseUrl = "https://api.example.com/v1"
        )

        assertPolicyDenied(result)
        assertEquals(6, server.requestCount)
    }

    // Mutation caught: sharing the configuration deadline with extractor work or omitting extractor cancellation.
    @Test(timeout = 3_000)
    fun `extractor infinite loop hits its own wall clock deadline`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://api.example.com/x"},extractor:function(r){while(true){}}})""",
            timeout = 1
        )

        val result = UsageScriptExecutor.extractForTest(script, account("https://api.example.com"), "{}")

        assertTrue(result is ScriptExecutionResult.Failure)
        assertTrue((result as ScriptExecutionResult.Failure).failure is RefreshFailure.ScriptTimeout)
    }

    private suspend fun execute(
        requestUrl: String,
        baseUrl: String,
        authorizedOrigins: Set<String> = emptySet(),
        resolver: HostResolver = PUBLIC_RESOLVER
    ): ScriptExecutionResult {
        val script = UsageScript(
            """({request:{url:"$requestUrl"},extractor:function(r){return r;}})""",
            timeout = 1
        )
        return UsageScriptExecutor.execute(
            script = script,
            account = account(baseUrl, authorizedOrigins),
            resolver = resolver,
            client = client,
            connectionUrlOverride = ::routeToTestServer
        )
    }

    private fun routeToTestServer(logicalUrl: HttpUrl): HttpUrl = server.url(logicalUrl.encodedPath)
        .newBuilder()
        .encodedQuery(logicalUrl.encodedQuery)
        .build()

    private fun account(
        baseUrl: String,
        authorizedOrigins: Set<String> = emptySet()
    ) = AccountInfo(
        id = "account-id",
        label = "Primary",
        apiKey = "api-key-123456",
        providerType = ProviderType.CUSTOM,
        extraSettings = mapOf("baseUrl" to baseUrl),
        usageScriptEnabled = true,
        authorizedScriptOrigins = authorizedOrigins
    )

    private fun successResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"remaining":12.5,"unit":"USD"}""")

    private fun assertSuccess(result: ScriptExecutionResult) {
        assertTrue("unexpected result: $result", result is ScriptExecutionResult.Success)
        assertEquals(12.5, (result as ScriptExecutionResult.Success).balances.single().remaining!!, 0.0)
    }

    private fun assertPolicyDenied(result: ScriptExecutionResult) {
        assertTrue("unexpected result: $result", result is ScriptExecutionResult.Failure)
        assertTrue((result as ScriptExecutionResult.Failure).failure is RefreshFailure.ScriptPolicyDenied)
    }

    private class FixedResolver(
        private val addresses: Map<String, List<String>>
    ) : HostResolver {
        override fun lookup(host: String): List<InetAddress> =
            addresses[host].orEmpty().map(InetAddress::getByName)
    }

    private companion object {
        val PUBLIC_RESOLVER = FixedResolver(
            mapOf(
                "api.example.com" to listOf("93.184.216.34"),
                "cdn.example.com" to listOf("93.184.216.35"),
                "evil.example.com" to listOf("93.184.216.36")
            )
        )
    }
}
