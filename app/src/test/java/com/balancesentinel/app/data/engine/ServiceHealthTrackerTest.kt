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

    // ═══════════════════════════════════════════════════════════
    // recordSuccess
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `recordSuccess resets consecutive failures to zero`() {
        ServiceHealthTracker.recordFailure(context)
        ServiceHealthTracker.recordFailure(context)
        assertEquals(2, ServiceHealthTracker.getConsecutiveFailures(context))

        ServiceHealthTracker.recordSuccess(context)
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `recordSuccess exits protection mode`() {
        // Simulate entering protection mode by setting failures >= 10
        repeat(10) { ServiceHealthTracker.recordFailure(context) }
        assertTrue(ServiceHealthTracker.isInProtectionMode(context))

        ServiceHealthTracker.recordSuccess(context)
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    // ═══════════════════════════════════════════════════════════
    // recordFailure
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `recordFailure increments consecutive failures`() {
        ServiceHealthTracker.recordFailure(context)
        assertEquals(1, ServiceHealthTracker.getConsecutiveFailures(context))

        ServiceHealthTracker.recordFailure(context)
        assertEquals(2, ServiceHealthTracker.getConsecutiveFailures(context))

        ServiceHealthTracker.recordFailure(context)
        assertEquals(3, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `recordFailure at threshold does not throw`() {
        // 3 failures = alert threshold (sends notification, caught internally)
        repeat(3) { ServiceHealthTracker.recordFailure(context) }
        assertEquals(3, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    @Test
    fun `recordFailure at protection threshold enters protection mode`() {
        repeat(10) { ServiceHealthTracker.recordFailure(context) }
        assertTrue(ServiceHealthTracker.isInProtectionMode(context))
    }

    @Test
    fun `recordFailure does not enter protection mode before threshold`() {
        repeat(9) { ServiceHealthTracker.recordFailure(context) }
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    // ═══════════════════════════════════════════════════════════
    // getConsecutiveFailures
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getConsecutiveFailures returns zero initially`() {
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
    }

    // ═══════════════════════════════════════════════════════════
    // isInProtectionMode
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `isInProtectionMode returns false initially`() {
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }

    // ═══════════════════════════════════════════════════════════
    // reset
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `reset clears failures and protection mode`() {
        repeat(12) { ServiceHealthTracker.recordFailure(context) }
        assertTrue(ServiceHealthTracker.isInProtectionMode(context))
        assertTrue(ServiceHealthTracker.getConsecutiveFailures(context) > 0)

        ServiceHealthTracker.reset(context)
        assertEquals(0, ServiceHealthTracker.getConsecutiveFailures(context))
        assertFalse(ServiceHealthTracker.isInProtectionMode(context))
    }
}
