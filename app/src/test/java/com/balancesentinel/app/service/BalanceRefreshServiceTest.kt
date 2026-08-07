package com.balancesentinel.app.service

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceRefreshServiceTest {

    @Test
    fun `task removal restart delegates to foreground service starter`() {
        val service = Robolectric.buildService(BalanceRefreshService::class.java).get()
        val starter = RecordingStarter()
        service.serviceStarter = starter

        service.onTaskRemoved(null)

        assertEquals(1, starter.calls)
    }

    @Test
    fun `service does not retain legacy ApiKeyManager account reader`() {
        assertTrue(BalanceRefreshService::class.java.declaredFields.none { it.type.name.endsWith("ApiKeyManager") })
    }

    private class RecordingStarter : ServiceStarter {
        var calls = 0

        override fun start(context: Context): ServiceStartResult {
            calls += 1
            return ServiceStartResult.Started
        }
    }
}
