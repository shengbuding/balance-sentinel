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
import java.io.IOException
import java.util.concurrent.TimeUnit

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
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            service.getBalance("sk-test-key")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `getBalance throws on empty response body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        try {
            service.getBalance("sk-test-key")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Empty response body", e.message)
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
}
