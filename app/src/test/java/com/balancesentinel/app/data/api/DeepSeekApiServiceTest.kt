package com.balancesentinel.app.data.api

import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.UsageResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DeepSeekApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: DeepSeekApiService
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = DeepSeekApiService(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getBalance parses valid response correctly`() {
        val balanceInfo = BalanceInfo(
            currency = "CNY", totalBalance = "123.45",
            grantedBalance = "100.00", toppedUpBalance = "23.45"
        )
        val response = BalanceResponse(isAvailable = true, balanceInfos = listOf(balanceInfo))
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(response))
            .setResponseCode(200))

        val result = service.getBalance("sk-test-key")
        assertTrue(result.isAvailable)
        assertEquals(1, result.balanceInfos.size)
        assertEquals("123.45", result.balanceInfos[0].totalBalance)
    }

    @Test
    fun `getBalance throws on HTTP 401`() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            service.getBalance("sk-invalid-key")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `getBalance throws on HTTP 429`() {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            service.getBalance("sk-test-key")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun `getBalance throws on HTTP 500`() {
        // RetryInterceptor retries 5xx; enqueue 3 so the last one propagates
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            service.getBalance("sk-test-key")
            fail("Expected exception")
        } catch (e: Exception) {
            assertNotNull(e)
        }
    }

    @Test
    fun `getBalance throws on empty response body`() {
        // setBody("") returns empty string (not null), so JSON parsing fails
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        try {
            service.getBalance("sk-test-key")
            fail("Expected exception")
        } catch (e: Exception) {
            // kotlinx.serialization JsonDecodingException on empty body
            assertNotNull(e)
        }
    }

    @Test
    fun `getBalance throws on malformed JSON`() {
        server.enqueue(MockResponse().setBody("{not valid json").setResponseCode(200))
        try {
            service.getBalance("sk-test-key")
            fail("Expected parsing exception")
        } catch (e: Exception) {
            // kotlinx.serialization SerializationException or IllegalStateException
            assertNotNull(e)
        }
    }

    @Test
    fun `getBalance throws on HTTP 403`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        try {
            service.getBalance("sk-test-key")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // getUsage
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getUsage parses valid response`() {
        val usageResponse = UsageResponse(
            data = listOf(
                com.balancesentinel.app.data.model.UsageRecord(
                    model_name = "deepseek-chat",
                    total_tokens = 1500,
                    prompt_tokens = 1000,
                    completion_tokens = 500
                )
            )
        )
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        val result = service.getUsage("sk-test-key")
        assertEquals(1, result.data.size)
        assertEquals("deepseek-chat", result.data[0].model_name)
        assertEquals(1500L, result.data[0].total_tokens)
        assertEquals(1000L, result.data[0].prompt_tokens)
    }

    @Test
    fun `getUsage with startDate builds correct URL`() {
        val usageResponse = UsageResponse(data = emptyList())
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        service.getUsage("sk-test-key", startDate = "2026-07-01")
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("start_date=2026-07-01"))
        assertFalse(request.path!!.contains("end_date"))
    }

    @Test
    fun `getUsage with both dates builds correct URL`() {
        val usageResponse = UsageResponse(data = emptyList())
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        service.getUsage("sk-test-key", startDate = "2026-07-01", endDate = "2026-07-08")
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("start_date=2026-07-01"))
        assertTrue(request.path!!.contains("end_date=2026-07-08"))
    }

    @Test
    fun `getUsage with only endDate builds correct URL`() {
        val usageResponse = UsageResponse(data = emptyList())
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        service.getUsage("sk-test-key", endDate = "2026-07-08")
        val request = server.takeRequest()
        assertFalse(request.path!!.contains("start_date"))
        assertTrue(request.path!!.contains("end_date=2026-07-08"))
    }

    @Test
    fun `getUsage without dates has no query params`() {
        val usageResponse = UsageResponse(data = emptyList())
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        service.getUsage("sk-test-key")
        val request = server.takeRequest()
        assertEquals("/v1/usage", request.path)
    }

    @Test
    fun `getUsage throws on HTTP 401`() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            service.getUsage("sk-invalid")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `getUsage throws on HTTP 429`() {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            service.getUsage("sk-test-key")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun `getUsage throws on empty response body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        try {
            service.getUsage("sk-test-key")
            fail("Expected exception")
        } catch (e: Exception) {
            assertNotNull(e)
        }
    }

    @Test
    fun `getUsage throws on malformed JSON`() {
        server.enqueue(MockResponse().setBody("{bad").setResponseCode(200))
        try {
            service.getUsage("sk-test-key")
            fail("Expected parsing exception")
        } catch (e: Exception) {
            assertNotNull(e)
        }
    }

    @Test
    fun `getUsage handles empty data list`() {
        val usageResponse = UsageResponse(data = emptyList())
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        val result = service.getUsage("sk-test-key")
        assertTrue(result.data.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // RetryInterceptor behavior
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getBalance succeeds on retry after 500`() {
        val balanceInfo = BalanceInfo(
            currency = "CNY", totalBalance = "50.00",
            grantedBalance = "30.00", toppedUpBalance = "20.00"
        )
        val response = BalanceResponse(isAvailable = true, balanceInfos = listOf(balanceInfo))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(response))
            .setResponseCode(200))

        val result = service.getBalance("sk-test-key")
        assertEquals("50.00", result.balanceInfos[0].totalBalance)
    }

    @Test
    fun `getUsage succeeds on retry after 500`() {
        val usageResponse = UsageResponse(
            data = listOf(com.balancesentinel.app.data.model.UsageRecord(model_name = "m1", total_tokens = 100))
        )
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse()
            .setBody(json.encodeToString(usageResponse))
            .setResponseCode(200))

        val result = service.getUsage("sk-test-key")
        assertEquals(1, result.data.size)
        assertEquals("m1", result.data[0].model_name)
    }

    @Test
    fun `getBalance retry exhausts after 3 x 500`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            service.getBalance("sk-test-key")
            fail("Expected exception after retry exhausted")
        } catch (e: Exception) {
            assertNotNull(e)
        }
    }
}
