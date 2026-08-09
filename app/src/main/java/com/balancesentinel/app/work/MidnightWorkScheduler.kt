package com.balancesentinel.app.work

import android.content.Context
import com.balancesentinel.app.data.repository.MidnightScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Description of one recoverable midnight maintenance request. */
data class MidnightWorkSpec(
    val uniqueName: String,
    val delayMillis: Long,
    val input: Map<String, String> = emptyMap()
)

/** Injectable WorkManager boundary for midnight maintenance. */
interface MidnightWorkRuntime {
    fun enqueueOneShot(context: Context, spec: MidnightWorkSpec)
    fun cancelUnique(context: Context, uniqueName: String)
}

/** Compatibility adapter used by the support commit; it delegates to the old Alarm chain. */
object LegacyMidnightWorkRuntime : MidnightWorkRuntime {
    override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
        MidnightScheduler.schedule(context)
    }

    override fun cancelUnique(context: Context, uniqueName: String) {
        MidnightScheduler.cancel(context)
    }
}

/**
 * Scheduler seam for the one-shot midnight maintenance chain.
 *
 * The support implementation intentionally keeps the old alarm behavior through
 * [LegacyMidnightWorkRuntime]. Task 17's GREEN implementation swaps the runtime
 * for a WorkManager-backed one while retaining this stable API.
 */
class MidnightWorkScheduler(
    private val runtime: MidnightWorkRuntime = LegacyMidnightWorkRuntime,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun reconcile(
        context: Context,
        now: Instant = clock.instant(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ) {
        runtime.enqueueOneShot(
            context,
            MidnightWorkSpec(
                uniqueName = UNIQUE_WORK_NAME,
                delayMillis = 0L,
                input = mapOf(KEY_ZONE_ID to zoneId.id, KEY_RECONCILED_AT to now.toEpochMilli().toString())
            )
        )
    }

    fun cancel(context: Context) = runtime.cancelUnique(context, UNIQUE_WORK_NAME)

    companion object {
        const val UNIQUE_WORK_NAME = "midnight-maintenance"
        const val KEY_ZONE_ID = "midnight_zone_id"
        const val KEY_RECONCILED_AT = "midnight_reconciled_at"
        const val KEY_TARGET_DATE = "midnight_target_date"
    }
}
