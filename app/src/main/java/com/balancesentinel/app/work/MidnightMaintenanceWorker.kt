package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.data.util.Logger
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
    var zoneIdProvider: () -> ZoneId = ZoneId::systemDefault
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
        zoneIdProvider = ZoneId::systemDefault
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
        val requestedZoneId = try {
            val serializedZone = inputData.getString(MidnightWorkScheduler.KEY_ZONE_ID)
            if (serializedZone == null) ZoneId.systemDefault() else ZoneId.of(serializedZone)
        } catch (error: Exception) {
            return retry("invalid_zone_input", error)
        }
        val zoneId = try {
            MidnightMaintenanceDependencies.zoneIdProvider()
        } catch (error: Exception) {
            return retry("active_zone_read_failed", error)
        }
        if (requestedZoneId != zoneId) {
            Logger.i("MidnightMaintenanceWorker", "stale_zone_request_rebased")
        }
        val nowMillis = try {
            val serializedNow = inputData.getString(MidnightWorkScheduler.KEY_NOW_MILLIS)
            if (serializedNow != null) {
                serializedNow.toLong()
            } else {
                inputData.getLong(MidnightWorkScheduler.KEY_NOW_MILLIS, Long.MIN_VALUE)
                    .takeUnless { it == Long.MIN_VALUE }
            }
        } catch (error: Exception) {
            return retry("invalid_now_input", error)
        }
        val now = try {
            nowMillis?.let(Instant::ofEpochMilli) ?: MidnightMaintenanceDependencies.clock.instant()
        } catch (error: Exception) {
            return retry("clock_read_failed", error)
        }
        val today = now.atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val checkpointStore = try {
            MidnightMaintenanceDependencies.checkpointStoreFactory(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return retry("checkpoint_store_factory_failed", error)
        }
        val checkpoint = try {
            checkpointStore.read(zoneId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return retry("checkpoint_read_failed", error)
        }
        val nextDate = if (checkpoint.zoneId != zoneId) {
            yesterday
        } else {
            checkpoint.lastCompletedDate?.plusDays(1) ?: yesterday
        }

        if (nextDate.isAfter(yesterday)) {
            return if (scheduleReconcile(zoneId)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }

        val report = try {
            MidnightMaintenanceDependencies.cleanupRunner.run(applicationContext, nextDate, zoneId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return retry("cleanup_failed", error)
        }
        if (report.failures.isNotEmpty()) {
            Logger.w(
                "MidnightMaintenanceWorker",
                "cleanup_report_failed:${report.failures.size}"
            )
            return ListenableWorker.Result.retry()
        }

        val zoneAfterCleanup = try {
            MidnightMaintenanceDependencies.zoneIdProvider()
        } catch (error: Exception) {
            return retry("active_zone_recheck_failed", error)
        }
        if (zoneAfterCleanup != zoneId) {
            return if (scheduleReconcile(zoneAfterCleanup)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }

        val marked = try {
            checkpointStore.markCompletedIfCurrent(
                expected = checkpoint,
                date = nextDate,
                zoneId = zoneId,
                successAt = now.toEpochMilli()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return retry("checkpoint_write_failed", error)
        }
        if (!marked) {
            // Another worker won the compare-and-set. Do not enqueue with the
            // stale worker's zone/policy and cancel the winner's request.
            val current = try {
                checkpointStore.read(zoneId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return retry("checkpoint_conflict_read_failed", error)
            }
            return if (current.lastCompletedDate?.let { !it.isBefore(nextDate) } == true) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }
        val schedulingZone = try {
            MidnightMaintenanceDependencies.zoneIdProvider()
        } catch (error: Exception) {
            return retry("active_zone_schedule_check_failed", error)
        }
        if (schedulingZone != zoneId) {
            return if (scheduleReconcile(schedulingZone)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }
        val after = nextDate.plusDays(1)
        if (after.isAfter(yesterday)) {
            return if (scheduleReconcile(zoneId)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        } else {
            return if (scheduleNext(now, zoneId, after)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }
    }

    private fun scheduleNext(now: Instant, zoneId: ZoneId, targetDate: LocalDate): Boolean = try {
        MidnightWorkSchedulingGate.withLock {
            val activeZone = MidnightMaintenanceDependencies.zoneIdProvider()
            val callback = MidnightMaintenanceDependencies.reenqueue
            if (callback != null && activeZone == zoneId) {
                callback(applicationContext)
            } else if (activeZone != zoneId) {
                MidnightMaintenanceDependencies.schedulerFactory(applicationContext)
                    .reconcile(
                        applicationContext,
                        MidnightMaintenanceDependencies.clock.instant(),
                        activeZone,
                        MidnightWorkPolicy.REPLACE
                    )
            } else {
                MidnightMaintenanceDependencies.schedulerFactory(applicationContext)
                    .enqueueImmediate(applicationContext, now, activeZone, targetDate)
            }
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Logger.w("MidnightMaintenanceWorker", "next_enqueue_failed", error)
        false
    }

    private fun scheduleReconcile(zoneId: ZoneId): Boolean = try {
        MidnightWorkSchedulingGate.withLock {
            val activeZone = MidnightMaintenanceDependencies.zoneIdProvider()
            val callback = MidnightMaintenanceDependencies.reenqueue
            if (callback != null && activeZone == zoneId) {
                callback(applicationContext)
            } else {
                MidnightMaintenanceDependencies.schedulerFactory(applicationContext)
                    .reconcile(
                        applicationContext,
                        MidnightMaintenanceDependencies.clock.instant(),
                        activeZone,
                        MidnightWorkPolicy.REPLACE
                    )
            }
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Logger.w("MidnightMaintenanceWorker", "next_reconcile_failed", error)
        false
    }

    private fun retry(reason: String, error: Exception): ListenableWorker.Result {
        Logger.w("MidnightMaintenanceWorker", reason, error)
        return ListenableWorker.Result.retry()
    }
}
