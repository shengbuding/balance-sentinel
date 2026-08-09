package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.work.MidnightWorkRuntime
import com.balancesentinel.app.work.MidnightWorkPolicy
import com.balancesentinel.app.work.MidnightWorkScheduler
import com.balancesentinel.app.work.MidnightWorkSpec
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkReconcileReceiverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `boot package replacement and timezone change reconcile one unique work without opening home`() {
        val runtime = RecordingRuntime()
        val policies = mutableListOf<MidnightWorkPolicy>()
        val receiver = WorkReconcileReceiver(object : WorkReconcileDelegate {
            override fun reconcile(context: Context) {
                policies += MidnightWorkPolicy.KEEP
            }

            override fun reconcile(context: Context, policy: MidnightWorkPolicy) {
                policies += policy
                MidnightWorkScheduler(runtime).reconcile(context, policy = policy)
            }
        })

        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED
        ).forEach { receiver.onReceive(context, Intent(it)) }

        assertEquals(1, runtime.specs.size)
        assertEquals(MidnightWorkScheduler.UNIQUE_WORK_NAME, runtime.specs.keys.single())
        assertEquals(
            listOf(MidnightWorkPolicy.KEEP, MidnightWorkPolicy.KEEP, MidnightWorkPolicy.REPLACE),
            policies
        )
        assertEquals(MidnightWorkPolicy.REPLACE, runtime.singleKey())
    }

    @Test
    fun `unrelated broadcasts do not reconcile`() {
        var calls = 0
        WorkReconcileReceiver(WorkReconcileDelegate { calls++ })
            .onReceive(context, Intent("com.example.OTHER"))

        assertEquals(0, calls)
    }

    private class RecordingRuntime : MidnightWorkRuntime {
        val specs = linkedMapOf<String, MidnightWorkSpec>()
        val policies = linkedMapOf<String, MidnightWorkPolicy>()

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
            specs[spec.uniqueName] = spec
        }

        override fun enqueueOneShot(
            context: Context,
            spec: MidnightWorkSpec,
            policy: MidnightWorkPolicy
        ) {
            policies[spec.uniqueName] = policy
            enqueueOneShot(context, spec)
        }

        override fun cancelUnique(context: Context, uniqueName: String) {
            specs.remove(uniqueName)
        }

        fun singleKey(): MidnightWorkPolicy = policies.getValue(MidnightWorkScheduler.UNIQUE_WORK_NAME)
    }
}
