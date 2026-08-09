package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.work.MidnightWorkRuntime
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
        val scheduler = MidnightWorkScheduler(runtime)
        val receiver = WorkReconcileReceiver(WorkReconcileDelegate { scheduler.reconcile(context) })

        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED
        ).forEach { receiver.onReceive(context, Intent(it)) }

        assertEquals(1, runtime.specs.size)
        assertEquals(MidnightWorkScheduler.UNIQUE_WORK_NAME, runtime.specs.keys.single())
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

        override fun enqueueOneShot(context: Context, spec: MidnightWorkSpec) {
            specs[spec.uniqueName] = spec
        }

        override fun cancelUnique(context: Context, uniqueName: String) {
            specs.remove(uniqueName)
        }
    }
}
