package com.balancesentinel.app.data.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServiceHealthTrackerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceHealthTracker.reset(context)
    }

    @After
    fun tearDown() {
        ServiceHealthTracker.reset(context)
    }

    @Test
    fun `initial state has zero failures and no protection mode`() {
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    @Test
    fun `recordSuccess resets failure count to zero`() {
        ServiceHealthTracker.recordFailure(context)
        ServiceHealthTracker.recordFailure(context)
        assertEquals(2, ServiceHealthTracker.getConsecutiveFailures(context))

        ServiceHealthTracker.recordSuccess(context)
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `recordSuccess exits protection mode`() {
        val prefs = context.getSharedPreferences("service_health", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("protection_mode", true).putInt("consecutive_failures", 10).apply()

        assertTrue(ServiceHealthTracker.isInProtectionMode(context))

        ServiceHealthTracker.recordSuccess(context)
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `recordFailure increments failure count`() {
        ServiceHealthTracker.recordFailure(context)
        assertEquals(1, ServiceHealthTracker.getConsecutiveFailures(context))

        ServiceHealthTracker.recordFailure(context)
        assertEquals(2, ServiceHealthTracker.getConsecutiveFailures(context))

        ServiceHealthTracker.recordFailure(context)
        assertEquals(3, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `isInProtectionMode returns false by default`() {
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    @Test
    fun `getConsecutiveFailures returns zero by default`() {
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `reset clears failures and protection mode`() {
        val prefs = context.getSharedPreferences("service_health", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("consecutive_failures", 5)
            .putBoolean("protection_mode", true)
            .apply()

        ServiceHealthTracker.reset(context)

        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    @Test
    fun `recordSuccess when not in protection mode is no-op aside from reset`() {
        ServiceHealthTracker.recordFailure(context)
        ServiceHealthTracker.recordSuccess(context)
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    @Test
    fun `multiple success calls stay at zero`() {
        ServiceHealthTracker.recordSuccess(context)
        ServiceHealthTracker.recordSuccess(context)
        ServiceHealthTracker.recordSuccess(context)
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
    }
}
