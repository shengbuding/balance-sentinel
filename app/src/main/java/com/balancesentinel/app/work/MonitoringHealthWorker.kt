package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.refresh.RefreshRuntime
import com.balancesentinel.app.service.ContinuousMonitoringController
import com.balancesentinel.app.service.MonitoringStateStore
import com.balancesentinel.app.service.PersistentBalanceNotificationPublisher
import kotlinx.coroutines.CancellationException

object MonitoringHealthWorkerDependencies {
    var controllerFactory: (Context) -> ContinuousMonitoringController = { context ->
        val database = WalletDatabaseProvider.get(context)
        ContinuousMonitoringController(database, MonitoringStateStore.from(context).processSessionId)
    }
    var reconcile: suspend (Context) -> Unit = { context ->
        controllerFactory(context).observedState()
    }
    var desiredReader: suspend (Context) -> Boolean = { context ->
        MonitoringStateStore.from(context).get().desired
    }
    var notificationPublisher: suspend (Context) -> Unit = { context ->
        PersistentBalanceNotificationPublisher.from(context)
            .publishCached(RefreshRuntime.from(context))
    }

    fun reset() {
        controllerFactory = { context ->
            val database = WalletDatabaseProvider.get(context)
            ContinuousMonitoringController(database, MonitoringStateStore.from(context).processSessionId)
        }
        reconcile = { context -> controllerFactory(context).observedState() }
        desiredReader = { context -> MonitoringStateStore.from(context).get().desired }
        notificationPublisher = { context ->
            PersistentBalanceNotificationPublisher.from(context)
                .publishCached(RefreshRuntime.from(context))
        }
    }
}

/** Reconciles the projection; it never starts a foreground service by itself. */
class MonitoringHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result = try {
        MonitoringHealthWorkerDependencies.reconcile(applicationContext)
        if (MonitoringHealthWorkerDependencies.desiredReader(applicationContext)) {
            try {
                MonitoringHealthWorkerDependencies.notificationPublisher(applicationContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Projection health succeeded; notification delivery remains best-effort.
            }
        }
        ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        ListenableWorker.Result.retry()
    }
}
