package com.balancesentinel.app.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Description of one recoverable midnight maintenance request. */
data class MidnightWorkSpec(
    val uniqueName: String,
    val delayMillis: Long,
    val input: Map<String, String> = emptyMap()
)

enum class MidnightWorkPolicy {
    KEEP,
    REPLACE
}

/** Injectable WorkManager boundary for midnight maintenance. */
interface MidnightWorkRuntime {
    fun enqueueOneShot(context: Context, spec: MidnightWorkSpec)

    fun enqueueOneShot(
        context: Context,
        spec: MidnightWorkSpec,
        policy: MidnightWorkPolicy
    ) = enqueueOneShot(context, spec)

    fun cancelUnique(context: Context, uniqueName: String)
}

/** Production WorkManager implementation for the unique midnight chain. */
object DefaultMidnightWorkRuntime : MidnightWorkRuntime {
    override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
        enqueueOneShot(context, spec, MidnightWorkPolicy.REPLACE)
    }

    override fun enqueueOneShot(
        context: Context,
        spec: MidnightWorkSpec,
        policy: MidnightWorkPolicy
    ) {
        val input = Data.Builder().apply {
            spec.input.forEach { (key, value) -> putString(key, value) }
        }.build()
        val request = OneTimeWorkRequest.Builder(MidnightMaintenanceWorker::class.java)
            .setInitialDelay(spec.delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(MidnightWorkScheduler.UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                spec.uniqueName,
                when (policy) {
                    MidnightWorkPolicy.KEEP -> ExistingWorkPolicy.KEEP
                    MidnightWorkPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
                },
                request
            )
    }

    override fun cancelUnique(context: Context, uniqueName: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName)
    }
}

/**
 * Scheduler seam for the one-shot midnight maintenance chain.
 *
 * The runtime is injectable for deterministic behavior tests while production
 * always uses WorkManager's unique one-shot queue.
 */
class MidnightWorkScheduler(
    private val runtime: MidnightWorkRuntime = DefaultMidnightWorkRuntime,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun reconcile(
        context: Context,
        now: Instant = clock.instant(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ) {
        val next = nextLocalMidnight(now, zoneId)
        val targetDate = next.atZone(zoneId).toLocalDate()
        runtime.enqueueOneShot(
            context,
            MidnightWorkSpec(
                uniqueName = UNIQUE_WORK_NAME,
                delayMillis = Duration.between(now, next).toMillis().coerceAtLeast(0L),
                input = mapOf(
                    KEY_ZONE_ID to zoneId.id,
                    KEY_RECONCILED_AT to now.toEpochMilli().toString(),
                    KEY_TARGET_DATE to targetDate.toString()
                )
            )
        )
    }

    fun enqueueImmediate(
        context: Context,
        now: Instant = clock.instant(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        targetDate: java.time.LocalDate? = null
    ) {
        runtime.enqueueOneShot(
            context,
            MidnightWorkSpec(
                uniqueName = UNIQUE_WORK_NAME,
                delayMillis = 0L,
                input = buildMap {
                    put(KEY_ZONE_ID, zoneId.id)
                    put(KEY_RECONCILED_AT, now.toEpochMilli().toString())
                    targetDate?.let { put(KEY_TARGET_DATE, it.toString()) }
                    put(KEY_NOW_MILLIS, now.toEpochMilli().toString())
                }
            )
        )
    }

    fun cancel(context: Context) = runtime.cancelUnique(context, UNIQUE_WORK_NAME)

    /** Computes the next local midnight without assuming a fixed 24-hour day. */
    fun nextLocalMidnight(now: Instant = clock.instant(), zoneId: ZoneId = ZoneId.systemDefault()): Instant =
        nextLocalMidnightInstant(now, zoneId)

    companion object {
        const val UNIQUE_WORK_NAME = "midnight-maintenance"
        const val KEY_ZONE_ID = "midnight_zone_id"
        const val KEY_RECONCILED_AT = "midnight_reconciled_at"
        const val KEY_TARGET_DATE = "midnight_target_date"
        const val KEY_NOW_MILLIS = "midnight_now_millis"

        fun nextLocalMidnight(now: Instant, zoneId: ZoneId): Instant =
            nextLocalMidnightInstant(now, zoneId)

        private fun nextLocalMidnightInstant(now: Instant, zoneId: ZoneId): Instant =
            now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant()
    }
}
