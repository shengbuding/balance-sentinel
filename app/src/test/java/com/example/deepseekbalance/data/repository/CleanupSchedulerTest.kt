package com.example.deepseekbalance.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.deepseekbalance.data.model.RawRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CleanupSchedulerTest {

    private lateinit var context: Context
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    @After
    fun tearDown() {
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    @Test
    fun `runCleanup aggregates old records and creates summaries`() = runTest {
        val oldTimestamp = System.currentTimeMillis() - 25 * 3600_000L  // 25h ago
        val yesterday = dateFormat.format(Date(oldTimestamp))
        RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp + 1000L, "CNY", 90f, 0f, 90f))

        CleanupScheduler.runCleanup(context)

        assertTrue(DailySummaryStore.hasSummaryForDate(context, yesterday, "CNY", "acc1"))
        // Records >24h deleted
        val remaining = RawRecordStore.getRecordsForDate(context, yesterday)
        assertEquals(0, remaining.size)
    }

    @Test
    fun `runCleanup preserves records younger than 24h`() = runTest {
        val recentTimestamp = System.currentTimeMillis() - 1 * 3600_000L  // 1h ago
        val today = dateFormat.format(Date())
        RawRecordStore.addRecord(context, RawRecord("acc1", recentTimestamp, "CNY", 100f, 0f, 100f))

        CleanupScheduler.runCleanup(context)

        val remaining = RawRecordStore.getAllRecords(context)
        assertEquals(1, remaining.size)  // <24h, preserved
        assertFalse(DailySummaryStore.hasSummaryForDate(context, today, "CNY", "acc1"))  // today not summarized
    }

    @Test
    fun `runCleanup fills date gaps`() = runTest {
        val t3 = System.currentTimeMillis() - 3 * 24 * 3600_000L  // 3 days ago
        val day1 = dateFormat.format(Date(t3))

        // Only day-3 has records
        RawRecordStore.addRecord(context, RawRecord("acc1", t3, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", t3 + 1000L, "CNY", 90f, 0f, 90f))

        CleanupScheduler.runCleanup(context)

        // Real data day should have a summary with sampleCount > 0
        val all = DailySummaryStore.getSummaries(context).sortedBy { it.date }
        assertTrue(all.any { it.date == day1 && it.sampleCount > 0 })
    }
}
