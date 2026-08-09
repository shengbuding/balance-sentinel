package com.balancesentinel.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduledRetries.clear()
        RefreshWorkerDependencies.gatewayFactory = { fakeGateway() }
        RefreshWorkerDependencies.retryScheduler = { scheduledRetries += it }
    }

    @After
    fun tearDown() {
        RefreshWorkerDependencies.reset()
    }

    @Test
    fun `periodic worker invokes unified refresh engine and retries only retryable partial failures`() = runTest {
        val worker = TestListenableWorkerBuilder<RefreshWorker>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(RetrySchedule("network", attempt = 1, delayMillis = 30_000L)), scheduledRetries)
    }

    @Test
    fun `one shot retry worker refreshes only its account and does not retry permanent failure`() = runTest {
        var refreshedAccount: String? = null
        RefreshWorkerDependencies.gatewayFactory = {
            object : RefreshGateway by fakeGateway() {
                override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult {
                    refreshedAccount = accountId
                    return AccountRefreshResult.Failed(
                        accountId,
                        RefreshFailure.AuthenticationFailure("bad credential")
                    )
                }
            }
        }
        val worker = TestListenableWorkerBuilder<RefreshWorker>(context)
            .setInputData(RefreshWorker.retryInput("permanent", attempt = 1))
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("permanent", refreshedAccount)
        assertTrue(scheduledRetries.isEmpty())
    }

    private fun fakeGateway(): RefreshGateway = object : RefreshGateway {
        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger): AccountRefreshResult =
            AccountRefreshResult.Committed(accountId, fakeBalance())

        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult {
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

    private fun fakeBalance() = UnifiedBalance(
        totalBalance = "1.00",
        currency = "USD",
        grantedBalance = "1.00",
        toppedUpBalance = "0.00",
        isAvailable = true
    )
}