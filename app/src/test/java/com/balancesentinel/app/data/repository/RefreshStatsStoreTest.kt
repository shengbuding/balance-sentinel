package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.engine.ServiceHealthTracker
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshStatsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RefreshStatsStore.reset(context)
        ServiceHealthTracker.reset(context)
    }

    @After
    fun tearDown() {
        RefreshStatsStore.reset(context)
        ServiceHealthTracker.reset(context)
    }

    // ═══════════════════════════════════════════════════════════
    // recordSuccess
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `recordSuccess increments success count`() {
        RefreshStatsStore.recordSuccess(context)
        val stats = RefreshStatsStore.getStats(context)
        assertEquals(1, stats.successes)
        assertEquals(1, stats.totalAttempts)
    }

    @Test
    fun `recordSuccess sets lastSuccessTime and lastAttemptTime`() {
        val before = System.currentTimeMillis()
        RefreshStatsStore.recordSuccess(context)
        val stats = RefreshStatsStore.getStats(context)
        assertTrue(stats.lastSuccessTime >= before)
        assertTrue(stats.lastAttemptTime >= before)
    }

    // ═══════════════════════════════════════════════════════════
    // recordFailure
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `recordFailure increments failure count`() {
        RefreshStatsStore.recordFailure(context)
        RefreshStatsStore.recordFailure(context)
        val stats = RefreshStatsStore.getStats(context)
        assertEquals(2, stats.failures)
        assertEquals(0, stats.successes)
        assertEquals(2, stats.totalAttempts)
    }

    @Test
    fun `recordFailure sets lastAttemptTime but not lastSuccessTime`() {
        RefreshStatsStore.recordFailure(context)
        val stats = RefreshStatsStore.getStats(context)
        assertTrue(stats.lastAttemptTime > 0)
        assertEquals(0, stats.lastSuccessTime)
    }

    // ═══════════════════════════════════════════════════════════
    // recordSkipped
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `recordSkipped increments skipped count`() {
        RefreshStatsStore.recordSkipped(context)
        RefreshStatsStore.recordSkipped(context)
        RefreshStatsStore.recordSkipped(context)
        val stats = RefreshStatsStore.getStats(context)
        assertEquals(3, stats.skipped)
        assertEquals(3, stats.totalAttempts)
    }

    // ═══════════════════════════════════════════════════════════
    // getStats — combined
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getStats returns all counters correctly`() {
        RefreshStatsStore.recordSuccess(context)
        RefreshStatsStore.recordSuccess(context)
        RefreshStatsStore.recordFailure(context)
        RefreshStatsStore.recordSkipped(context)
        RefreshStatsStore.recordSuccess(context)

        val stats = RefreshStatsStore.getStats(context)
        assertEquals(3, stats.successes)
        assertEquals(1, stats.failures)
        assertEquals(1, stats.skipped)
        assertEquals(5, stats.totalAttempts)
    }

    @Test
    fun `getStats returns zero for all fields initially`() {
        val stats = RefreshStatsStore.getStats(context)
        assertEquals(0, stats.totalAttempts)
        assertEquals(0, stats.successes)
        assertEquals(0, stats.failures)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.consecutiveFailures)
        assertEquals(0, stats.lastSuccessTime)
        assertEquals(0, stats.lastAttemptTime)
    }

    @Test
    fun `successRate returns minus one when no non-skipped attempts`() {
        // only skipped — no successes or failures
        RefreshStatsStore.recordSkipped(context)
        val stats = RefreshStatsStore.getStats(context)
        assertEquals(-1, stats.successRate)
    }

    @Test
    fun `successRate after first attempt`() {
        RefreshStatsStore.recordSuccess(context)
        assertEquals(100, RefreshStatsStore.getStats(context).successRate)
    }

    @Test
    fun `successRate calculates correctly with mixed outcomes`() {
        // 7 successes, 3 failures → 70%
        repeat(7) { RefreshStatsStore.recordSuccess(context) }
        repeat(3) { RefreshStatsStore.recordFailure(context) }
        assertEquals(70, RefreshStatsStore.getStats(context).successRate)
    }

    @Test
    fun `successRate returns 0 when all failed`() {
        RefreshStatsStore.recordFailure(context)
        RefreshStatsStore.recordFailure(context)
        assertEquals(0, RefreshStatsStore.getStats(context).successRate)
    }

    // ═══════════════════════════════════════════════════════════
    // reset
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `reset clears all counters`() {
        repeat(5) { RefreshStatsStore.recordSuccess(context) }
        repeat(3) { RefreshStatsStore.recordFailure(context) }
        RefreshStatsStore.recordSkipped(context)

        RefreshStatsStore.reset(context)

        val stats = RefreshStatsStore.getStats(context)
        assertEquals(0, stats.successes)
        assertEquals(0, stats.failures)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.totalAttempts)
    }

    // ═══════════════════════════════════════════════════════════
    // ring buffer overflow
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ring buffer handles overflow beyond 100 records`() {
        // write 150 records — buffer should cap at 100
        repeat(150) { RefreshStatsStore.recordSuccess(context) }
        val stats = RefreshStatsStore.getStats(context)
        // counters are unbounded — they track all-time
        assertEquals(150, stats.successes)
        assertEquals(150, stats.totalAttempts)
    }

    @Test
    fun `ring buffer preserves counters beyond buffer size`() {
        // Fill 200 records — counters should keep exact totals
        repeat(100) { RefreshStatsStore.recordSuccess(context) }
        repeat(50) { RefreshStatsStore.recordFailure(context) }
        repeat(50) { RefreshStatsStore.recordSkipped(context) }

        val stats = RefreshStatsStore.getStats(context)
        assertEquals(100, stats.successes)
        assertEquals(50, stats.failures)
        assertEquals(50, stats.skipped)
        assertEquals(200, stats.totalAttempts)
    }

    // ═══════════════════════════════════════════════════════════
    // consecutive failures (delegated to ServiceHealthTracker)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getStats reports consecutive failures from ServiceHealthTracker`() {
        ServiceHealthTracker.recordFailure(context)
        ServiceHealthTracker.recordFailure(context)
        ServiceHealthTracker.recordFailure(context)

        val stats = RefreshStatsStore.getStats(context)
        assertEquals(3, stats.consecutiveFailures)
    }

    @Test
    fun `consecutive failures reset after reset`() {
        ServiceHealthTracker.recordFailure(context)
        ServiceHealthTracker.recordFailure(context)

        ServiceHealthTracker.reset(context)
        assertEquals(0, RefreshStatsStore.getStats(context).consecutiveFailures)
    }
}
