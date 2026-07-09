package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.RawRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawRecordStoreTest {

    private lateinit var context: Context
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RawRecordStore.clear(context)
    }

    @After
    fun tearDown() {
        RawRecordStore.clear(context)
    }

    @Test
    fun `add and retrieve single record`() {
        val record = RawRecord("acc1", now, "CNY", 100f, 10f, 90f)
        RawRecordStore.addRecord(context, record)

        val todayRecords = RawRecordStore.getTodayRecords(context)
        assertEquals(1, todayRecords.size)
        assertEquals(100f, todayRecords[0].totalBalance)
    }

    @Test
    fun `add multiple records preserves order`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", now + 1000, "CNY", 90f, 0f, 90f))
        RawRecordStore.addRecord(context, RawRecord("acc1", now + 2000, "CNY", 80f, 0f, 80f))

        val records = RawRecordStore.getTodayRecords(context)
        assertEquals(3, records.size)
        assertEquals(100f, records[0].totalBalance)
        assertEquals(80f, records[2].totalBalance)
    }

    @Test
    fun `clear removes all records`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))
        RawRecordStore.clear(context)
        assertTrue(RawRecordStore.getTodayRecords(context).isEmpty())
    }

    // ── removeRecords (精确删除) ──

    @Test
    fun `removeRecords removes matching records exactly`() {
        val r1 = RawRecord("acc1", now, "CNY", 100f, 0f, 100f)
        val r2 = RawRecord("acc1", now + 1000, "CNY", 90f, 0f, 90f)
        RawRecordStore.addRecord(context, r1)
        RawRecordStore.addRecord(context, r2)

        RawRecordStore.removeRecords(context, listOf(r1))

        val remaining = RawRecordStore.getTodayRecords(context)
        assertEquals(1, remaining.size)
        assertEquals(90f, remaining[0].totalBalance)
    }

    @Test
    fun `removeRecords with multiple records removes all matched`() {
        val r1 = RawRecord("acc1", now, "CNY", 100f, 0f, 100f)
        val r2 = RawRecord("acc1", now + 1000, "CNY", 90f, 0f, 90f)
        val r3 = RawRecord("acc1", now + 2000, "CNY", 80f, 0f, 80f)
        RawRecordStore.addRecord(context, r1)
        RawRecordStore.addRecord(context, r2)
        RawRecordStore.addRecord(context, r3)

        RawRecordStore.removeRecords(context, listOf(r1, r2))

        val remaining = RawRecordStore.getTodayRecords(context)
        assertEquals(1, remaining.size)
        assertEquals(80f, remaining[0].totalBalance)
    }

    @Test
    fun `removeRecords with non-existent records is no-op`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))

        val nonExistent = RawRecord("acc1", now + 99999, "CNY", 50f, 0f, 50f)
        RawRecordStore.removeRecords(context, listOf(nonExistent))

        assertEquals(1, RawRecordStore.getTodayRecords(context).size)
    }

    @Test
    fun `removeRecords with empty list does nothing`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))
        RawRecordStore.removeRecords(context, emptyList())

        assertEquals(1, RawRecordStore.getTodayRecords(context).size)
    }

    @Test
    fun `removeRecords preserves records from different accounts`() {
        val acc1Record = RawRecord("acc1", now, "CNY", 100f, 0f, 100f)
        val acc2Record = RawRecord("acc2", now, "CNY", 200f, 0f, 200f)
        RawRecordStore.addRecord(context, acc1Record)
        RawRecordStore.addRecord(context, acc2Record)

        // Only remove acc1's record
        RawRecordStore.removeRecords(context, listOf(acc1Record))

        val remaining = RawRecordStore.getTodayRecords(context)
        assertEquals(1, remaining.size)
        assertEquals("acc2", remaining[0].accountId)
    }

    @Test
    fun `getTodayRecordsForAccount filters by accountId`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc2", now + 1000, "CNY", 200f, 0f, 200f))
        RawRecordStore.addRecord(context, RawRecord("acc1", now + 2000, "USD", 50f, 0f, 50f))

        val acc1Records = RawRecordStore.getTodayRecordsForAccount(context, "acc1")
        assertEquals(2, acc1Records.size)
        assertTrue(acc1Records.all { it.accountId == "acc1" })

        val acc2Records = RawRecordStore.getTodayRecordsForAccount(context, "acc2")
        assertEquals(1, acc2Records.size)
        assertEquals("acc2", acc2Records[0].accountId)
    }

    @Test
    fun `getAllRecordsForAccount returns all records for an account`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc2", now + 1000, "CNY", 200f, 0f, 200f))

        val allAcc1 = RawRecordStore.getAllRecordsForAccount(context, "acc1")
        assertEquals(1, allAcc1.size)
        assertEquals("acc1", allAcc1[0].accountId)
    }

    @Test
    fun `getTodayRecords returns empty when store is empty`() {
        assertTrue(RawRecordStore.getTodayRecords(context).isEmpty())
    }

    @Test
    fun `getAllRecords returns empty when store is empty`() {
        assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
        assertTrue(RawRecordStore.getAllRecordsForAccount(context, "nonexistent").isEmpty())
    }

    // ── getRecordsSince ──

    @Test
    fun `getRecordsSince filters by timestamp`() {
        val t0 = System.currentTimeMillis()
        RawRecordStore.addRecord(context, RawRecord("acc1", t0 - 2 * 3600_000L, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", t0 - 3600_000L, "CNY", 90f, 0f, 90f))
        RawRecordStore.addRecord(context, RawRecord("acc1", t0, "CNY", 80f, 0f, 80f))

        // query records within last 30 minutes — only the most recent record
        val recent = RawRecordStore.getRecordsSince(context, t0 - 30 * 60_000L)
        assertEquals(1, recent.size)
        assertEquals(80f, recent[0].totalBalance)
    }

    // ── getRecordsForDate ──

    @Test
    fun `getRecordsForDate groups by calendar date`() {
        val yesterdayMidnight = System.currentTimeMillis() - 24 * 3600_000L
        RawRecordStore.addRecord(context, RawRecord("acc1", yesterdayMidnight, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", yesterdayMidnight + 3600_000L, "CNY", 90f, 0f, 90f))

        val yesterday = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(yesterdayMidnight))
        val dateRecords = RawRecordStore.getRecordsForDate(context, yesterday)
        assertEquals(2, dateRecords.size)
    }

    // ── getDistinctDates ──

    @Test
    fun `getDistinctDates returns unique dates`() {
        val now = System.currentTimeMillis()
        val day1 = now - 3 * 24 * 3600_000L
        val day2 = now - 24 * 3600_000L

        RawRecordStore.addRecord(context, RawRecord("acc1", day1, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", day1 + 1000L, "CNY", 90f, 0f, 90f))
        RawRecordStore.addRecord(context, RawRecord("acc1", day2, "CNY", 80f, 0f, 80f))

        val dates = RawRecordStore.getDistinctDates(context)
        assertTrue(dates.size >= 2)
    }

    // ── removeByDate ──

    @Test
    fun `removeByDate deletes only old records of specified date`() {
        val now = System.currentTimeMillis()
        val oldTimestamp = now - 25 * 3600_000L  // 25h ago
        val recentTimestamp = now - 1 * 3600_000L // 1h ago
        val oldDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(oldTimestamp))

        RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", recentTimestamp, "CNY", 80f, 0f, 80f))

        RawRecordStore.removeByDate(context, oldDate)  // default minAgeMs = 24h

        // old record deleted
        val oldRecords = RawRecordStore.getRecordsForDate(context, oldDate)
        assertEquals(0, oldRecords.size)

        // recent record preserved
        val allRecords = RawRecordStore.getAllRecords(context)
        assertEquals(1, allRecords.size)
        assertEquals(80f, allRecords[0].totalBalance)
    }

    @Test
    fun `removeByDate keeps records younger than minAgeMs`() {
        val now = System.currentTimeMillis()
        val recentTimestamp = now - 1 * 3600_000L  // 1h ago
        val recentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(recentTimestamp))

        RawRecordStore.addRecord(context, RawRecord("acc1", recentTimestamp, "CNY", 100f, 0f, 100f))

        RawRecordStore.removeByDate(context, recentDate)  // default minAgeMs = 24h

        // records <24h old are preserved
        val records = RawRecordStore.getAllRecords(context)
        assertEquals(1, records.size)
    }

    // ── addRecords (batch) ──

    @Test
    fun `addRecords batch inserts multiple records at once`() {
        val records = listOf(
            RawRecord("acc1", now, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", now + 1000L, "CNY", 90f, 0f, 90f),
            RawRecord("acc2", now, "USD", 50f, 0f, 50f)
        )
        RawRecordStore.addRecords(context, records)
        val all = RawRecordStore.getAllRecords(context)
        assertEquals(3, all.size)
    }

    @Test
    fun `addRecords with empty list is no-op`() {
        RawRecordStore.addRecords(context, emptyList())
        assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
    }

    // ── no auto-clear on date change ──

    @Test
    fun `records no longer auto-clear on date change`() {
        val oldTimestamp = System.currentTimeMillis() - 25 * 3600_000L  // yesterday
        RawRecordStore.addRecord(context, RawRecord("acc1", oldTimestamp, "CNY", 100f, 0f, 100f))

        val todayTimestamp = System.currentTimeMillis()
        RawRecordStore.addRecord(context, RawRecord("acc1", todayTimestamp, "CNY", 90f, 0f, 90f))

        // Both yesterday's and today's records persist
        val all = RawRecordStore.getAllRecords(context)
        assertEquals(2, all.size)
    }
}
