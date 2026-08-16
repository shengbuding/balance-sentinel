package com.balancesentinel.app.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MonitoringHealthWorkerTest {
    private lateinit var context: Context
    private var reconciles = 0
    private var publishes = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reconciles = 0
        publishes = 0
        MonitoringHealthWorkerDependencies.reconcile = { reconciles++ }
        MonitoringHealthWorkerDependencies.desiredReader = { true }
        MonitoringHealthWorkerDependencies.notificationPublisher = { publishes++ }
    }

    @After
    fun tearDown() {
        MonitoringHealthWorkerDependencies.reset()
    }

    @Test
    fun `desired monitoring republishes retained notification`() = runTest {
        val worker = TestListenableWorkerBuilder.from(context, MonitoringHealthWorker::class.java).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, reconciles)
        assertEquals(1, publishes)
    }

    @Test
    fun `disabled monitoring never republishes notification`() = runTest {
        MonitoringHealthWorkerDependencies.desiredReader = { false }
        val worker = TestListenableWorkerBuilder.from(context, MonitoringHealthWorker::class.java).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, reconciles)
        assertEquals(0, publishes)
    }

    @Test
    fun `notification failure does not fail monitoring reconciliation`() = runTest {
        MonitoringHealthWorkerDependencies.notificationPublisher = { error("notifications blocked") }
        val worker = TestListenableWorkerBuilder.from(context, MonitoringHealthWorker::class.java).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, reconciles)
    }
}
