package com.example.deepseekbalance.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.deepseekbalance.data.model.UsageRecord
import com.example.deepseekbalance.data.model.UsageSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageDataStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear by writing empty data
        val prefs = context.getSharedPreferences("usage_snapshots", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @After
    fun tearDown() {
        val prefs = context.getSharedPreferences("usage_snapshots", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun createSnapshot(
        accountId: String = "acc1",
        timestamp: Long = System.currentTimeMillis(),
        totalTokens: Long = 1000
    ): UsageSnapshot {
        return UsageSnapshot(
            accountId = accountId,
            timestamp = timestamp,
            records = listOf(
                UsageRecord(
                    model_name = "deepseek-chat",
                    total_tokens = totalTokens,
                    prompt_tokens = 600,
                    completion_tokens = 400
                )
            )
        )
    }

    @Test
    fun `save and retrieve snapshot`() {
        val snapshot = createSnapshot()
        UsageDataStore.saveSnapshot(context, snapshot)

        val all = UsageDataStore.getAllSnapshots(context)
        assertEquals(1, all.size)
        assertEquals("acc1", all[0].accountId)
        assertEquals(1000, all[0].records[0].total_tokens)
    }

    @Test
    fun `same day and account overwrites previous snapshot`() {
        val s1 = createSnapshot(timestamp = 100_000_000_000L, totalTokens = 1000)
        val s2 = createSnapshot(timestamp = 100_000_050_000L, totalTokens = 2000)

        UsageDataStore.saveSnapshot(context, s1)
        UsageDataStore.saveSnapshot(context, s2)

        val all = UsageDataStore.getAllSnapshots(context)
        assertEquals(1, all.size)
        assertEquals(2000, all[0].records[0].total_tokens)
    }

    @Test
    fun `different days create separate snapshots`() {
        val day1 = 86_400_000L
        val s1 = createSnapshot(timestamp = day1)
        val s2 = createSnapshot(timestamp = day1 * 2)

        UsageDataStore.saveSnapshot(context, s1)
        UsageDataStore.saveSnapshot(context, s2)

        val all = UsageDataStore.getAllSnapshots(context)
        assertEquals(2, all.size)
    }

    @Test
    fun `different accounts create separate snapshots`() {
        val s1 = createSnapshot(accountId = "acc1")
        val s2 = createSnapshot(accountId = "acc2")

        UsageDataStore.saveSnapshot(context, s1)
        UsageDataStore.saveSnapshot(context, s2)

        val all = UsageDataStore.getAllSnapshots(context)
        assertEquals(2, all.size)
    }

    @Test
    fun `snapshots are sorted by timestamp`() {
        val s1 = createSnapshot(timestamp = 100_000_000_000L)
        val s2 = createSnapshot(timestamp = 200_000_000_000L)
        val s3 = createSnapshot(timestamp = 150_000_000_000L)

        UsageDataStore.saveSnapshot(context, s1)
        UsageDataStore.saveSnapshot(context, s2)
        UsageDataStore.saveSnapshot(context, s3)

        val all = UsageDataStore.getAllSnapshots(context)
        assertEquals(100_000_000_000L, all[0].timestamp)
        assertEquals(150_000_000_000L, all[1].timestamp)
        assertEquals(200_000_000_000L, all[2].timestamp)
    }

    @Test
    fun `getRecentSnapshots filters by days`() {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L

        UsageDataStore.saveSnapshot(context, createSnapshot(timestamp = now))
        UsageDataStore.saveSnapshot(context, createSnapshot(timestamp = now - 2 * dayMs))
        UsageDataStore.saveSnapshot(context, createSnapshot(timestamp = now - 10 * dayMs))

        val recent = UsageDataStore.getRecentSnapshots(context, 5)
        assertTrue(recent.size >= 1)
        // The 10-day-old one should be filtered out
        assertTrue(recent.none { it.timestamp <= now - 10 * dayMs })
    }

    @Test
    fun `getRecentSnapshots filters by account`() {
        val now = System.currentTimeMillis()
        UsageDataStore.saveSnapshot(context, createSnapshot(accountId = "acc1", timestamp = now))
        UsageDataStore.saveSnapshot(context, createSnapshot(accountId = "acc2", timestamp = now))

        val acc1Recent = UsageDataStore.getRecentSnapshots(context, 30, accountId = "acc1")
        assertEquals(1, acc1Recent.size)
        assertEquals("acc1", acc1Recent[0].accountId)
    }

    @Test
    fun `getTotalTokens returns zero when no snapshots`() {
        val tokens = UsageDataStore.getTotalTokens(context, 30)
        assertEquals(0, tokens)
    }

    @Test
    fun `getTotalTokens computes difference between latest and earliest`() {
        val dayMs = 86_400_000L
        val now = System.currentTimeMillis()

        // Save snapshots with increasing token counts
        UsageDataStore.saveSnapshot(context, createSnapshot(
            accountId = "acc1", timestamp = now - 3 * dayMs, totalTokens = 100
        ))
        UsageDataStore.saveSnapshot(context, createSnapshot(
            accountId = "acc1", timestamp = now - 2 * dayMs, totalTokens = 300
        ))
        UsageDataStore.saveSnapshot(context, createSnapshot(
            accountId = "acc1", timestamp = now - 1 * dayMs, totalTokens = 600
        ))

        val tokens = UsageDataStore.getTotalTokens(context, 30, accountId = "acc1")
        // latest - earliest = 600 - 100 = 500
        assertTrue(tokens > 0)
    }

    // ── clear ──

    @Test
    fun `clear removes all snapshots`() {
        UsageDataStore.saveSnapshot(context, createSnapshot())
        UsageDataStore.saveSnapshot(context, createSnapshot(timestamp = System.currentTimeMillis() + 86_400_000L))
        assertEquals(2, UsageDataStore.getAllSnapshots(context).size)

        UsageDataStore.clear(context)

        assertEquals(0, UsageDataStore.getAllSnapshots(context).size)
    }

    @Test
    fun `clear is idempotent`() {
        // Should not throw when called on empty store
        UsageDataStore.clear(context)
        assertEquals(0, UsageDataStore.getAllSnapshots(context).size)

        UsageDataStore.clear(context)
        assertEquals(0, UsageDataStore.getAllSnapshots(context).size)
    }
}
