package com.balancesentinel.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `legacy runtime cannot silently downgrade keep policy`() {
        val legacyRuntime = LegacyReplaceRuntime()
        val now = Instant.parse("2026-08-10T12:00:00Z")
        val scheduler = MidnightWorkScheduler(legacyRuntime, Clock.fixed(now, ZoneId.of("UTC")))

        val keepFailure = runCatching {
            scheduler.reconcile(context, now, ZoneId.of("UTC"), MidnightWorkPolicy.KEEP)
        }.exceptionOrNull()
        scheduler.enqueueImmediate(context, now, ZoneId.of("UTC"))

        assertTrue(keepFailure is IllegalStateException)
        assertEquals(1, legacyRuntime.replaceCalls)
    }

    @Test
    fun `timezone replacement waits behind stale enqueue and wins unique queue`() {
        val runtime = BlockingRuntime()
        val now = Instant.parse("2026-08-10T12:00:00Z")
        val staleDone = AtomicReference<Throwable?>(null)
        val replacementDone = AtomicReference<Throwable?>(null)

        val stale = thread(start = true) {
            try {
                MidnightWorkScheduler(runtime).reconcile(
                    context,
                    now,
                    ZoneId.of("UTC"),
                    MidnightWorkPolicy.REPLACE
                )
            } catch (error: Throwable) {
                staleDone.set(error)
            }
        }
        assertTrue(runtime.firstEnqueueStarted.await(2, TimeUnit.SECONDS))

        val replacement = thread(start = true) {
            try {
                MidnightWorkScheduler(runtime).reconcile(
                    context,
                    now,
                    ZoneId.of("America/Los_Angeles"),
                    MidnightWorkPolicy.REPLACE
                )
            } catch (error: Throwable) {
                replacementDone.set(error)
            }
        }
        runtime.releaseFirstEnqueue.countDown()
        stale.join(2_000)
        replacement.join(2_000)

        assertEquals(null, staleDone.get())
        assertEquals(null, replacementDone.get())
        assertEquals(
            "America/Los_Angeles",
            runtime.specs[MidnightWorkScheduler.UNIQUE_WORK_NAME]?.input?.get(MidnightWorkScheduler.KEY_ZONE_ID)
        )
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

    private class LegacyReplaceRuntime : MidnightWorkRuntime {
        var replaceCalls = 0

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
            replaceCalls += 1
        }

        override fun cancelUnique(context: Context, uniqueName: String) = Unit
    }

    private class BlockingRuntime : MidnightWorkRuntime {
        val specs = linkedMapOf<String, MidnightWorkSpec>()
        val firstEnqueueStarted = CountDownLatch(1)
        val releaseFirstEnqueue = CountDownLatch(1)
        private var first = true

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
            if (first) {
                first = false
                firstEnqueueStarted.countDown()
                check(releaseFirstEnqueue.await(2, TimeUnit.SECONDS))
            }
            specs[spec.uniqueName] = spec
        }

        override fun enqueueOneShot(
            context: Context,
            spec: MidnightWorkSpec,
            policy: MidnightWorkPolicy
        ) = enqueueOneShot(context, spec)

        override fun cancelUnique(context: Context, uniqueName: String) = Unit
    }
}
