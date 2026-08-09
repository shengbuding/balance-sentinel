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

/** Injectable cleanup boundary for date-ordered midnight maintenance. */
fun interface MidnightCleanupRunner {
    suspend fun run(context: Context, date: LocalDate, zoneId: ZoneId): CleanupReport
}

object MidnightMaintenanceDependencies {
    var clock: Clock = Clock.systemDefaultZone()
    var cleanupRunner: MidnightCleanupRunner = MidnightCleanupRunner { context, _, zoneId ->
        com.balancesentinel.app.data.repository.CleanupScheduler.runCleanup(
            context = context,
            now = System.currentTimeMillis(),
            zoneId = zoneId
        )
    }

    var checkpointStoreFactory: (Context) -> MaintenanceCheckpointStore = ::RoomMaintenanceCheckpointStore
    var reenqueue: ((Context) -> Unit)? = null

    fun reset() {
        clock = Clock.systemDefaultZone()
        cleanupRunner = MidnightCleanupRunner { context, _, zoneId ->
            com.balancesentinel.app.data.repository.CleanupScheduler.runCleanup(
                context = context,
                now = System.currentTimeMillis(),
                zoneId = zoneId
            )
        }
        checkpointStoreFactory = ::RoomMaintenanceCheckpointStore
        reenqueue = null
    }
}

/** Support shell; behavior is wired in the Task 17 GREEN implementation. */
class MidnightMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result = ListenableWorker.Result.success()
}
