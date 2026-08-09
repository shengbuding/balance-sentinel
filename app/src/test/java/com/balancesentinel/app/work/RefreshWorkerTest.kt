package com.balancesentinel.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshWorkerTest {
    private lateinit var context: Context
    private val scheduledRetries = mutableListOf<RetrySchedule>()
    private val cancelledRetries = mutableListOf<String>()
    private val observedTriggers = mutableListOf<RefreshTrigger>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduledRetries.clear()
        cancelledRetries.clear()
        observedTriggers.clear()
        RefreshWorkerDependencies.gatewayFactory = { fakeGateway() }
        RefreshWorkerDependencies.retryPlanner = RefreshRetryPlanner(jitterMillis = { 0L })
        RefreshWorkerDependencies.retryScheduler = { scheduledRetries += it }
        RefreshWorkerDependencies.retryCanceller = { _, accountId -> cancelledRetries += accountId }
    }

    @After
    fun tearDown() {
        RefreshWorkerDependencies.reset()
    }

    @Test
    fun `periodic worker invokes unified refresh engine and retries only retryable partial failures`() = runTest {
        val worker = TestListenableWorkerBuilder.from(context, RefreshWorker::class.java).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(RetrySchedule("network", attempt = 1, delayMillis = 30_000L)), scheduledRetries)
        assertEquals(listOf("success", "permanent"), cancelledRetries)
        assertEquals(listOf(RefreshTrigger.BACKGROUND), observedTriggers)
    }

    @Test
    fun `one shot retry worker refreshes only its account and does not retry permanent failure`() = runTest {
        var refreshedAccount: String? = null
        RefreshWorkerDependencies.gatewayFactory = {
            object : RefreshGateway by fakeGateway() {
                override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult {
                    refreshedAccount = accountId
                    observedTriggers += trigger
                    return AccountRefreshResult.Failed(
                        accountId,
                        RefreshFailure.AuthenticationFailure("bad credential")
                    )
                }
            }
        }
        val worker = TestListenableWorkerBuilder.from(context, RefreshWorker::class.java)
            .setInputData(RefreshWorker.retryInput("permanent", attempt = 1))
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("permanent", refreshedAccount)
        assertTrue(scheduledRetries.isEmpty())
        assertEquals(listOf("permanent"), cancelledRetries)
        assertEquals(listOf(RefreshTrigger.BACKGROUND), observedTriggers)
    }

    private fun fakeGateway(): RefreshGateway = object : RefreshGateway {
        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult {
            observedTriggers += trigger
            return AccountRefreshResult.Committed(accountId, fakeBalance())
        }

        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
            observedTriggers += trigger
            val results = listOf(
                AccountRefreshResult.Committed("success", fakeBalance()),
                AccountRefreshResult.Failed(
                    "network",
                    RefreshFailure.NetworkFailure("offline")
                ),
                AccountRefreshResult.Failed(
                    "permanent",
                    RefreshFailure.AuthenticationFailure("bad token")
                )
            )
            return RefreshBatchResult("run", results, deriveRefreshBatchAggregate(results))
        }

        override fun invalidate(accountId: String) = Unit
    }

    private fun fakeBalance() = UnifiedBalance(provider = ProviderType.DEEPSEEK, accountId = "account", isAvailable = true, balances = listOf(BalanceEntry(currency = "USD", totalBalance = 1.0)))
}
