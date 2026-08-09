package com.balancesentinel.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.balancesentinel.app.data.repository.CleanupReport
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MidnightMaintenanceReviewRedTest {
    private lateinit var context: Context
    private lateinit var store: ReviewCheckpointStore
    private lateinit var runtime: ReviewRuntime
    private val dates = mutableListOf<LocalDate>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ReviewCheckpointStore(LocalDate.of(2026, 8, 8), ZoneId.of("UTC"))
        runtime = ReviewRuntime()
        dates.clear()
        MidnightMaintenanceDependencies.clock = Clock.fixed(
            Instant.parse("2026-08-10T12:00:00Z"),
            ZoneId.of("UTC")
        )
        MidnightMaintenanceDependencies.checkpointStoreFactory = { store }
        MidnightMaintenanceDependencies.schedulerFactory = { MidnightWorkScheduler(runtime) }
        MidnightMaintenanceDependencies.reenqueue = null
        MidnightMaintenanceDependencies.cleanupRunner = MidnightCleanupRunner { _, date, _ ->
            dates += date
            CleanupReport(emptySet(), 0, 0, emptyList())
        }
    }

    @After
    fun tearDown() {
        MidnightMaintenanceDependencies.reset()
    }

    @Test
    fun `worker completion requeues with replace while startup remains keep`() = runTest {
        val worker = worker(inputNow = Instant.parse("2026-08-10T12:00:00Z"))
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(MidnightWorkPolicy.REPLACE, runtime.policies[MidnightWorkScheduler.UNIQUE_WORK_NAME])
    }

    @Test
    fun `zone change rebases checkpoint instead of applying old-zone date`() = runTest {
        val worker = worker(
            zone = ZoneId.of("America/Los_Angeles"),
            inputNow = Instant.parse("2026-08-11T12:00:00Z")
        )
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf(LocalDate.of(2026, 8, 10)), dates)
    }

    @Test
    fun `checkpoint failure becomes retry instead of escaping worker`() = runTest {
        store.readFailure = IllegalStateException("transient checkpoint")
        val outcome = runCatching { worker(inputNow = Instant.parse("2026-08-10T12:00:00Z")).doWork() }
        assertTrue(outcome.isSuccess)
        assertEquals(ListenableWorker.Result.retry(), outcome.getOrThrow())
    }

    @Test
    fun `checkpoint write failure becomes retry after cleanup`() = runTest {
        store.markFailure = IllegalStateException("transient checkpoint write")
        val outcome = runCatching { worker(inputNow = Instant.parse("2026-08-10T12:00:00Z")).doWork() }
        assertTrue(outcome.isSuccess)
        assertEquals(ListenableWorker.Result.retry(), outcome.getOrThrow())
        assertEquals(listOf(LocalDate.of(2026, 8, 9)), dates)
    }

    @Test
    fun `string now input controls worker date`() = runTest {
        // The dependency clock is fixed on Aug 10. With the checkpoint already
        // at Aug 9, only the serialized Aug 11 input makes Aug 10 eligible.
        store.lastCompletedDate = LocalDate.of(2026, 8, 9)
        val worker = worker(inputNow = Instant.parse("2026-08-11T12:00:00Z"))
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf(LocalDate.of(2026, 8, 10)), dates)
    }

    private fun worker(
        zone: ZoneId = ZoneId.of("UTC"),
        inputNow: Instant
    ) = TestListenableWorkerBuilder.from(context, MidnightMaintenanceWorker::class.java)
        .setInputData(
            Data.Builder()
                .putString(MidnightWorkScheduler.KEY_ZONE_ID, zone.id)
                .putString(MidnightWorkScheduler.KEY_NOW_MILLIS, inputNow.toEpochMilli().toString())
                .build()
        )
        .build()

    private class ReviewRuntime : MidnightWorkRuntime {
        val policies = linkedMapOf<String, MidnightWorkPolicy>()

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) = Unit

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec, policy: MidnightWorkPolicy) {
            policies[spec.uniqueName] = policy
        }

        override fun cancelUnique(context: Context, uniqueName: String) = Unit
    }

    private class ReviewCheckpointStore(
        var lastCompletedDate: LocalDate?,
        var zoneId: ZoneId
    ) : MaintenanceCheckpointStore {
        var readFailure: Throwable? = null
        var markFailure: Throwable? = null

        override suspend fun read(zoneId: ZoneId): MaintenanceCheckpoint {
            readFailure?.let { throw it }
            return MaintenanceCheckpoint(lastCompletedDate, this.zoneId, null)
        }

        override suspend fun markCompleted(date: LocalDate, zoneId: ZoneId, successAt: Long): Boolean {
            markFailure?.let { throw it }
            lastCompletedDate = date
            this.zoneId = zoneId
            return true
        }
    }
}
