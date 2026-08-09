package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.data.repository.CleanupReport
import java.time.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Injectable cleanup boundary for date-ordered midnight maintenance. */
fun interface MidnightCleanupRunner {
    suspend fun run(context: Context, date: LocalDate, zoneId: ZoneId): CleanupReport
}

object MidnightMaintenanceDependencies {
    var clock: Clock = Clock.systemDefaultZone()
    var cleanupRunner: MidnightCleanupRunner = MidnightCleanupRunner { context, date, zoneId ->
        com.balancesentinel.app.data.repository.CleanupScheduler.runCleanupForDate(
            context = context,
            date = date,
            now = System.currentTimeMillis(),
            zoneId = zoneId
        )
    }

    var checkpointStoreFactory: (Context) -> MaintenanceCheckpointStore = ::RoomMaintenanceCheckpointStore
    var reenqueue: ((Context) -> Unit)? = null
    var schedulerFactory: (Context) -> MidnightWorkScheduler = { MidnightWorkScheduler() }

    fun reset() {
        clock = Clock.systemDefaultZone()
        cleanupRunner = MidnightCleanupRunner { context, date, zoneId ->
            com.balancesentinel.app.data.repository.CleanupScheduler.runCleanupForDate(
                context = context,
                date = date,
                now = System.currentTimeMillis(),
                zoneId = zoneId
            )
        }
        checkpointStoreFactory = ::RoomMaintenanceCheckpointStore
        reenqueue = null
        schedulerFactory = { MidnightWorkScheduler() }
    }
}

/** Support shell; behavior is wired in the Task 17 GREEN implementation. */
class MidnightMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result {
        val zoneId = inputData.getString(MidnightWorkScheduler.KEY_ZONE_ID)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val nowMillis = inputData.getString(MidnightWorkScheduler.KEY_NOW_MILLIS)
            ?.toLongOrNull()
            ?: inputData.getLong(MidnightWorkScheduler.KEY_NOW_MILLIS, Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE }
        val now = nowMillis
            ?.let(Instant::ofEpochMilli)
            ?: MidnightMaintenanceDependencies.clock.instant()
        val today = now.atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val checkpointStore = try {
            MidnightMaintenanceDependencies.checkpointStoreFactory(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ListenableWorker.Result.retry()
        }
        val checkpoint = try {
            checkpointStore.read(zoneId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ListenableWorker.Result.retry()
        }
        val nextDate = if (checkpoint.zoneId != zoneId) {
            yesterday
        } else {
            checkpoint.lastCompletedDate?.plusDays(1) ?: yesterday
        }

        if (nextDate.isAfter(yesterday)) {
            reconcileNext(now, zoneId)
            return ListenableWorker.Result.success()
        }

        val report = try {
            MidnightMaintenanceDependencies.cleanupRunner.run(applicationContext, nextDate, zoneId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ListenableWorker.Result.retry()
        }
        if (report.failures.isNotEmpty()) return ListenableWorker.Result.retry()

        try {
            checkpointStore.markCompleted(nextDate, zoneId, now.toEpochMilli())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ListenableWorker.Result.retry()
        }
        val after = nextDate.plusDays(1)
        if (after.isAfter(yesterday)) {
            reconcileNext(now, zoneId)
        } else {
            enqueueNext(now, zoneId, after)
        }
        return ListenableWorker.Result.success()
    }

    private fun enqueueNext(now: Instant, zoneId: ZoneId, targetDate: LocalDate) {
        val callback = MidnightMaintenanceDependencies.reenqueue
        if (callback != null) {
            callback(applicationContext)
        } else {
            MidnightMaintenanceDependencies.schedulerFactory(applicationContext)
                .enqueueImmediate(applicationContext, now, zoneId, targetDate)
        }
    }

    private fun reconcileNext(now: Instant, zoneId: ZoneId) {
        val callback = MidnightMaintenanceDependencies.reenqueue
        if (callback != null) {
            callback(applicationContext)
        } else {
            MidnightMaintenanceDependencies.schedulerFactory(applicationContext)
                .reconcile(
                    applicationContext,
                    now,
                    zoneId,
                    MidnightWorkPolicy.REPLACE
                )
        }
    }
}
