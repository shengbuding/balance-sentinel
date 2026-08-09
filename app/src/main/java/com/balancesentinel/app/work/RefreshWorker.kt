package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshRuntime
import com.balancesentinel.app.data.refresh.RefreshTrigger
import kotlinx.coroutines.CancellationException

/** Injectable seams for the worker's unified refresh engine and retry queue. */
object RefreshWorkerDependencies {
    var gatewayFactory: (Context) -> RefreshGateway = RefreshRuntime::from
    var retryPlanner: RefreshRetryPlanner = RefreshRetryPlanner()
    var retryScheduler: ((RetrySchedule) -> Unit)? = null

    fun scheduleRetry(context: Context, schedule: RetrySchedule) {
        val callback = retryScheduler
        if (callback != null) {
            callback(schedule)
        } else {
            RefreshWorkScheduler().scheduleRetry(context, schedule)
        }
    }

    fun reset() {
        gatewayFactory = RefreshRuntime::from
        retryPlanner = RefreshRetryPlanner()
        retryScheduler = null
    }
}

/** Runs the same [RefreshGateway] used by foreground and widget callers. */
class RefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            val gateway = RefreshWorkerDependencies.gatewayFactory(applicationContext)
            val accountId = inputData.getString(KEY_ACCOUNT_ID)
            val previousAttempt = inputData.getInt(KEY_ATTEMPT, 0)
            if (accountId == null) {
                val batch = gateway.refreshAll(RefreshTrigger.SERVICE)
                batch.results.forEach { result ->
                    enqueueRetryIfNeeded(result.accountId, result, previousAttempt)
                }
            } else {
                val result = gateway.refreshAccount(accountId, RefreshTrigger.SERVICE)
                enqueueRetryIfNeeded(accountId, result, previousAttempt)
            }
            ListenableWorker.Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_WORKER_RETRY_ATTEMPTS) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }

    private fun enqueueRetryIfNeeded(
        accountId: String,
        result: AccountRefreshResult,
        previousAttempt: Int
    ) {
        RefreshWorkerDependencies.retryPlanner
            .next(accountId, result, previousAttempt)
            ?.let { RefreshWorkerDependencies.scheduleRetry(applicationContext, it) }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "refresh_account_id"
        const val KEY_ATTEMPT = "refresh_attempt"
        const val MAX_WORKER_RETRY_ATTEMPTS = 3

        fun retryInput(accountId: String, attempt: Int): Data =
            Data.Builder()
                .putString(KEY_ACCOUNT_ID, accountId)
                .putInt(KEY_ATTEMPT, attempt)
                .build()
    }
}