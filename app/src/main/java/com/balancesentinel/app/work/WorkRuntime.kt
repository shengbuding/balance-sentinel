package com.balancesentinel.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Description of a periodic WorkManager request. */
data class PeriodicWorkSpec(
    val uniqueName: String,
    val intervalSeconds: Long,
    val requiresNetwork: Boolean,
    val input: Map<String, String> = emptyMap()
)

/** Description of a one-shot WorkManager request. */
data class OneShotWorkSpec(
    val uniqueName: String,
    val delayMillis: Long,
    val input: Map<String, String> = emptyMap(),
    val attempt: Int = 0
)

/**
 * Scheduler-facing WorkManager seam. Production delegates to WorkManager;
 * tests can record requests without opening WorkManager's SQLite database.
 */
interface WorkRuntime {
    fun enqueuePeriodic(context: Context, spec: PeriodicWorkSpec)
    fun enqueueOneShot(context: Context, spec: OneShotWorkSpec)
    fun cancelUnique(context: Context, uniqueName: String)
}

object DefaultWorkRuntime : WorkRuntime {
    override fun enqueuePeriodic(context: Context, spec: PeriodicWorkSpec) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (spec.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
            )
            .build()
        val input = Data.Builder().apply {
            spec.input.forEach { (key, value) -> putString(key, value) }
        }.build()
        val request = PeriodicWorkRequest.Builder(
            RefreshWorker::class.java,
            spec.intervalSeconds.coerceAtLeast(RefreshWorkScheduler.MIN_BACKGROUND_INTERVAL_SECONDS),
            TimeUnit.SECONDS
        )
            .setConstraints(constraints)
            .setInputData(input)
            .addTag(spec.uniqueName)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                spec.uniqueName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }

    override fun enqueueOneShot(context: Context, spec: OneShotWorkSpec) {
        val input = Data.Builder().apply {
            spec.input.forEach { (key, value) -> putString(key, value) }
            putInt(RefreshWorker.KEY_ATTEMPT, spec.attempt)
        }.build()
        val request = OneTimeWorkRequest.Builder(RefreshWorker::class.java)
            .setInitialDelay(spec.delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(spec.uniqueName)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(spec.uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelUnique(context: Context, uniqueName: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName)
    }
}