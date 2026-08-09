package com.balancesentinel.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.balancesentinel.app.data.repository.CleanupFailure
import com.balancesentinel.app.data.repository.CleanupReport
import com.balancesentinel.app.data.repository.CleanupStage
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
class MidnightMaintenanceWorkerTest {
    private lateinit var context: Context
    private lateinit var checkpoint: RecordingCheckpointStore
    private lateinit var runtime: RecordingRuntime
    private val executedDates = mutableListOf<LocalDate>()
    private var failingDate: LocalDate? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        checkpoint = RecordingCheckpointStore(LocalDate.of(2026, 8, 6))
        runtime = RecordingRuntime()
        executedDates.clear()
        failingDate = null
        MidnightMaintenanceDependencies.clock = Clock.fixed(
            Instant.parse("2026-08-10T12:00:00Z"),
            ZoneId.of("UTC")
        )
        MidnightMaintenanceDependencies.zoneIdProvider = { ZoneId.of("UTC") }
        MidnightMaintenanceDependencies.checkpointStoreFactory = { checkpoint }
        MidnightMaintenanceDependencies.reenqueue = null
        MidnightMaintenanceDependencies.schedulerFactory = { MidnightWorkScheduler(runtime) }
        MidnightMaintenanceDependencies.cleanupRunner = MidnightCleanupRunner { _, date, _ ->
            executedDates += date
            val failure = if (date == failingDate) {
                CleanupFailure(date.toString(), CleanupStage.WRITE_SUMMARY, "injected")
            } else null
            CleanupReport(
                archivedDates = emptySet(),
                deletedRecordCount = 0,
                retainedRecordCount = 0,
                failures = listOfNotNull(failure)
            )
        }
    }

    @After
    fun tearDown() {
        MidnightMaintenanceDependencies.reset()
    }

    @Test
    fun `delayed three days executes each date in order`() = runTest {
        repeat(3) {
            val result = worker().doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 7),
                LocalDate.of(2026, 8, 8),
                LocalDate.of(2026, 8, 9)
            ),
            executedDates
        )
        assertEquals(LocalDate.of(2026, 8, 9), checkpoint.lastCompletedDate)
    }

    @Test
    fun `second day failure advances only first day and retry resumes second`() = runTest {
        failingDate = LocalDate.of(2026, 8, 8)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(listOf(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 8)), executedDates)
        assertEquals(LocalDate.of(2026, 8, 7), checkpoint.lastCompletedDate)

        failingDate = null
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(LocalDate.of(2026, 8, 8), checkpoint.lastCompletedDate)
        assertTrue(executedDates.count { it == LocalDate.of(2026, 8, 7) } == 1)
    }

    private fun worker() = TestListenableWorkerBuilder.from(context, MidnightMaintenanceWorker::class.java)
        .setInputData(
            androidx.work.Data.Builder()
                .putString(MidnightWorkScheduler.KEY_ZONE_ID, "UTC")
                .putLong(MidnightWorkScheduler.KEY_NOW_MILLIS, Instant.parse("2026-08-10T12:00:00Z").toEpochMilli())
                .build()
        )
        .build()

    private class RecordingCheckpointStore(initial: LocalDate) : MaintenanceCheckpointStore {
        var lastCompletedDate: LocalDate? = initial

        override suspend fun read(zoneId: ZoneId): MaintenanceCheckpoint = MaintenanceCheckpoint(
            lastCompletedDate = lastCompletedDate,
            zoneId = zoneId,
            lastSuccessAt = null
        )

        override suspend fun markCompleted(date: LocalDate, zoneId: ZoneId, successAt: Long): Boolean {
            if (lastCompletedDate != null && !date.isAfter(lastCompletedDate)) return false
            lastCompletedDate = date
            return true
        }
    }

    private class RecordingRuntime : MidnightWorkRuntime {
        val specs = linkedMapOf<String, MidnightWorkSpec>()
        val policies = linkedMapOf<String, MidnightWorkPolicy>()

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
            specs[spec.uniqueName] = spec
        }

        override fun enqueueOneShot(
            context: Context,
            spec: MidnightWorkSpec,
            policy: MidnightWorkPolicy
        ) {
            policies[spec.uniqueName] = policy
            enqueueOneShot(context, spec)
        }

        override fun cancelUnique(context: Context, uniqueName: String) {
            specs.remove(uniqueName)
        }
    }
}
