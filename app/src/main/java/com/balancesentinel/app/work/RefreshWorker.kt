package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.data.refresh.RefreshGateway

/** Test-first seam for the WorkManager refresh worker. */
object RefreshWorkerDependencies {
    var gatewayFactory: (Context) -> RefreshGateway = {
        error("RefreshWorker dependencies are not configured")
    }
    var retryScheduler: (RetrySchedule) -> Unit = {}

    fun reset() {
        gatewayFactory = { error("RefreshWorker dependencies are not configured") }
        retryScheduler = {}
    }
}

class RefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result =
        ListenableWorker.Result.failure()

    companion object {
        const val KEY_ACCOUNT_ID = "refresh_account_id"
        const val KEY_ATTEMPT = "refresh_attempt"

        fun retryInput(accountId: String, attempt: Int): Data =
            Data.Builder()
                .putString(KEY_ACCOUNT_ID, accountId)
                .putInt(KEY_ATTEMPT, attempt)
                .build()
    }
}