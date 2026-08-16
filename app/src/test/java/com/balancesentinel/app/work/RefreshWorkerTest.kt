package com.balancesentinel.app.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RefreshWorkerTest {
    private lateinit var context: Context
    private val scheduledRetries = mutableListOf<RetrySchedule>()
    private val cancelledRetries = mutableListOf<String>()
    private val observedTriggers = mutableListOf<RefreshTrigger>()
    private var publishedNotifications = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduledRetries.clear()
        cancelledRetries.clear()
        observedTriggers.clear()
        publishedNotifications = 0
        RefreshWorkerDependencies.gatewayFactory = { fakeGateway() }
        RefreshWorkerDependencies.retryPlanner = RefreshRetryPlanner(jitterMillis = { 0L })
        RefreshWorkerDependencies.retryScheduler = { scheduledRetries += it }
        RefreshWorkerDependencies.retryCanceller = { _, accountId -> cancelledRetries += accountId }
        RefreshWorkerDependencies.notificationPublisher = { _, _ -> publishedNotifications++ }
        RefreshWorkerDependencies.monitoringDesiredReader = { true }
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
        assertEquals(1, publishedNotifications)
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
        assertEquals(1, publishedNotifications)
    }

    @Test
    fun `notification failure does not fail a committed background refresh`() = runTest {
        RefreshWorkerDependencies.notificationPublisher = { _, _ -> error("notifications blocked") }
        val worker = TestListenableWorkerBuilder.from(context, RefreshWorker::class.java).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(RefreshTrigger.BACKGROUND), observedTriggers)
    }

    @Test
    fun `disabled monitoring never republishes after a background refresh`() = runTest {
        RefreshWorkerDependencies.monitoringDesiredReader = { false }
        val worker = TestListenableWorkerBuilder.from(context, RefreshWorker::class.java).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(RefreshTrigger.BACKGROUND), observedTriggers)
        assertEquals(0, publishedNotifications)
    }

    @Test
    fun `foreground lease is healthy only for the current process session`() {
        val state = MonitoringStateEntity(
            desired = true,
            observedState = MonitoringObservedState.RUNNING,
            processSessionId = "old-process",
            leaseExpiresAt = 2_000L,
            updatedAt = 1L
        )

        assertFalse(isHealthyForegroundSession(state, "new-process", now = 1_000L))
        assertTrue(isHealthyForegroundSession(state, "old-process", now = 1_000L))
        assertFalse(isHealthyForegroundSession(state, "old-process", now = 2_000L))
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
