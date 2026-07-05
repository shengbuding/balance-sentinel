package com.example.deepseekbalance.data.repository

import com.example.deepseekbalance.data.api.DeepSeekApiService
import com.example.deepseekbalance.data.model.BalanceInfo
import com.example.deepseekbalance.data.model.BalanceResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BalanceRepositoryTest {

    private val mockApi = mockk<DeepSeekApiService>()
    private val repository = BalanceRepository(mockApi)

    @Test
    fun `fetchBalance returns success with valid response`() = runTest {
        val response = BalanceResponse(
            isAvailable = true,
            balanceInfos = listOf(
                BalanceInfo("CNY", "100.00", "10.00", "90.00")
            )
        )
        coEvery { mockApi.getBalance("valid-key") } returns response

        val result = repository.fetchBalance("valid-key")
        assertTrue(result.isSuccess)
        assertEquals(response, result.getOrNull())
    }

    @Test
    fun `fetchBalance returns failure on IOException`() = runTest {
        coEvery { mockApi.getBalance("bad-key") } throws IOException("Network error")

        val result = repository.fetchBalance("bad-key")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchBalance returns failure on generic exception wrapping to IOException`() = runTest {
        coEvery { mockApi.getBalance("any") } throws RuntimeException("Unexpected crash")

        val result = repository.fetchBalance("any")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IOException)
        assertTrue(ex?.message?.contains("Unexpected crash") == true)
    }

    @Test
    fun `fetchBalance returns failure on auth error 401`() = runTest {
        coEvery { mockApi.getBalance("expired") } throws IOException("API Key invalid")

        val result = repository.fetchBalance("expired")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `fetchBalance handles empty balance_infos`() = runTest {
        val response = BalanceResponse(isAvailable = true, balanceInfos = emptyList())
        coEvery { mockApi.getBalance("empty-key") } returns response

        val result = repository.fetchBalance("empty-key")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.balanceInfos?.isEmpty() == true)
    }
}
