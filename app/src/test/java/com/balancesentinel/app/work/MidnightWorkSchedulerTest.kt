package com.balancesentinel.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MidnightWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var runtime: RecordingMidnightRuntime

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runtime = RecordingMidnightRuntime()
    }

    @Test
    fun `scheduler uses next local midnight across spring DST transition`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T06:30:00Z")
        val scheduler = MidnightWorkScheduler(runtime, Clock.fixed(now, zone))

        scheduler.reconcile(context, now, zone)

        val spec = runtime.specs.values.single()
        assertEquals(MidnightWorkScheduler.UNIQUE_WORK_NAME, spec.uniqueName)
        assertEquals(
            Instant.parse("2026-03-09T04:00:00Z").toEpochMilli() - now.toEpochMilli(),
            spec.delayMillis
        )
        assertEquals("2026-03-09", spec.input[MidnightWorkScheduler.KEY_TARGET_DATE])
    }

    @Test
    fun `startup reconcile keeps existing work instead of replacing due work`() {
        val scheduler = MidnightWorkScheduler(runtime, Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneId.of("UTC")))

        scheduler.reconcile(context, zoneId = ZoneId.of("UTC"))

        assertEquals(MidnightWorkPolicy.KEEP, runtime.policies[MidnightWorkScheduler.UNIQUE_WORK_NAME])
    }

    @Test
    fun `reconcile remains one unique request after process reconstruction`() {
        val scheduler = MidnightWorkScheduler(runtime, Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneId.of("UTC")))

        repeat(3) { scheduler.reconcile(context, zoneId = ZoneId.of("UTC")) }

        assertEquals(1, runtime.specs.size)
    }

    private class RecordingMidnightRuntime : MidnightWorkRuntime {
        val specs = linkedMapOf<String, MidnightWorkSpec>()
        val policies = linkedMapOf<String, MidnightWorkPolicy>()

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
            specs[spec.uniqueName] = spec
        }

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec, policy: MidnightWorkPolicy) {
            policies[spec.uniqueName] = policy
            enqueueOneShot(context, spec)
        }

        override fun cancelUnique(context: Context, uniqueName: String) {
            specs.remove(uniqueName)
        }
    }
}
