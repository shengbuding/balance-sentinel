package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.service.ContinuousMonitoringController
import com.balancesentinel.app.service.MonitoringStateStore

object MonitoringHealthWorkerDependencies {
    var controllerFactory: (Context) -> ContinuousMonitoringController = { context ->
        val database = WalletDatabaseProvider.get(context)
        ContinuousMonitoringController(database, MonitoringStateStore.from(context).processSessionId)
    }

    fun reset() {
        controllerFactory = { context ->
            val database = WalletDatabaseProvider.get(context)
            ContinuousMonitoringController(database, MonitoringStateStore.from(context).processSessionId)
        }
    }
}

/** Reconciles the projection; it never starts a foreground service by itself. */
class MonitoringHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result = runCatching {
        MonitoringHealthWorkerDependencies.controllerFactory(applicationContext).observedState()
        ListenableWorker.Result.success()
    }.getOrElse { ListenableWorker.Result.retry() }
}
