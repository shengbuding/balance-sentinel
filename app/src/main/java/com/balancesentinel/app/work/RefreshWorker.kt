package com.balancesentinel.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshRuntime
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import kotlinx.coroutines.CancellationException

/** Injectable seams for the worker's unified refresh engine and retry queue. */
object RefreshWorkerDependencies {
    var gatewayFactory: (Context) -> RefreshGateway = RefreshRuntime::from
    var retryPlanner: RefreshRetryPlanner = RefreshRetryPlanner()
    var retryScheduler: ((RetrySchedule) -> Unit)? = null
    var retryCanceller: ((Context, String) -> Unit)? = null

    fun cancelRetry(context: Context, accountId: String) {
        retryCanceller?.invoke(context, accountId) ?: RefreshWorkScheduler().cancelRetries(context, accountId)
    }

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
        retryCanceller = null
    }
}

/** Runs the same [RefreshGateway] used by foreground and widget callers. */
class RefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result {
        val continuousRecovery =
            inputData.getString(RefreshWorkScheduler.KEY_CONTINUOUS_RECOVERY).toBoolean()
        return try {
            val gateway = RefreshWorkerDependencies.gatewayFactory(applicationContext)
            val accountId = inputData.getString(KEY_ACCOUNT_ID)
            val previousAttempt = inputData.getInt(KEY_ATTEMPT, 0)
            if (accountId == null) {
                if (!hasHealthyForegroundSession()) {
                    val batch = gateway.refreshAll(RefreshTrigger.BACKGROUND)
                    batch.results.forEach { result ->
                        enqueueRetryIfNeeded(result.accountId, result, previousAttempt)
                    }
                }
            } else {
                val result = gateway.refreshAccount(accountId, RefreshTrigger.BACKGROUND)
                enqueueRetryIfNeeded(accountId, result, previousAttempt)
            }
            if (continuousRecovery) continueContinuousRecovery()
            ListenableWorker.Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_WORKER_RETRY_ATTEMPTS) {
                ListenableWorker.Result.retry()
            } else {
                if (continuousRecovery) {
                    runCatching { continueContinuousRecovery() }
                }
                ListenableWorker.Result.failure()
            }
        }
    }

    private suspend fun hasHealthyForegroundSession(): Boolean {
        val state = runCatching {
            WalletDatabaseProvider.get(applicationContext).monitoringStateDao().get()
        }.getOrNull() ?: return false
        val processSessionId = (applicationContext as? DeepSeekApp)?.processSessionId ?: return false
        return isHealthyForegroundSession(
            state = state,
            currentProcessSessionId = processSessionId,
            now = System.currentTimeMillis()
        )
    }

    private suspend fun continueContinuousRecovery() {
        val interval = SettingsRepositoryProvider.get(applicationContext)
            .readSnapshot()
            .effectiveBackgroundCadenceSeconds
            ?.toLong()
        RefreshWorkScheduler().continueRecovery(applicationContext, interval)
    }

    private fun enqueueRetryIfNeeded(
        accountId: String,
        result: AccountRefreshResult,
        previousAttempt: Int
    ) {
        val nextRetry = RefreshWorkerDependencies.retryPlanner.next(accountId, result, previousAttempt)
        if (nextRetry != null) {
            RefreshWorkerDependencies.scheduleRetry(applicationContext, nextRetry)
        } else {
            RefreshWorkerDependencies.cancelRetry(applicationContext, accountId)
        }
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

internal fun isHealthyForegroundSession(
    state: MonitoringStateEntity,
    currentProcessSessionId: String,
    now: Long
): Boolean = state.desired &&
    state.observedState == MonitoringObservedState.RUNNING &&
    state.processSessionId == currentProcessSessionId &&
    (state.leaseExpiresAt ?: 0L) > now
