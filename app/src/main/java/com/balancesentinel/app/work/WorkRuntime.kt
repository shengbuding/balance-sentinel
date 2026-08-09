package com.balancesentinel.app.work

import android.content.Context

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
    override fun enqueuePeriodic(context: Context, spec: PeriodicWorkSpec) = Unit
    override fun enqueueOneShot(context: Context, spec: OneShotWorkSpec) = Unit
    override fun cancelUnique(context: Context, uniqueName: String) = Unit
}