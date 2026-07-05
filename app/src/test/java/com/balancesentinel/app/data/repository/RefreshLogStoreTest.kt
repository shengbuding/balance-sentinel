package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshLogStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RefreshLogStore.clear(context)
    }

    @After
    fun tearDown() {
        RefreshLogStore.clear(context)
    }

    @Test
    fun `add and retrieve single entry`() {
        val entry = RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000,
            totalBalance = "100", currency = "CNY", isAvailable = true
        )
        RefreshLogStore.addEntry(context, entry)
        val entries = RefreshLogStore.getEntries(context)
        assertEquals(1, entries.size)
        assertEquals("100", entries[0].totalBalance)
        assertEquals("CNY", entries[0].currency)
    }

    @Test
    fun `entries are ordered newest first`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000,
            totalBalance = "100", currency = "CNY"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000,
            totalBalance = "90", currency = "CNY"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 3, type = RefreshLogType.MANUAL, timestamp = 3000,
            totalBalance = "80", currency = "CNY"
        ))

        val entries = RefreshLogStore.getEntries(context)
        assertEquals(3, entries.size)
        assertEquals(3000, entries[0].timestamp) // newest first
        assertEquals(2000, entries[1].timestamp)
        assertEquals(1000, entries[2].timestamp) // oldest last
    }

    @Test
    fun `entries respect max limit`() {
        // logMaxEntries coerces to range 10..1000, so use minimum 10
        val prefs = WidgetPrefs(context)
        prefs.logMaxEntries = 10

        // Add 15 entries — only 10 should be kept
        repeat(15) { i ->
            RefreshLogStore.addEntry(context, RefreshLogEntry(
                id = i.toLong(), type = RefreshLogType.MANUAL, timestamp = i * 1000L,
                totalBalance = i.toString(), currency = "CNY"
            ))
        }

        val entries = RefreshLogStore.getEntries(context)
        assertEquals(10, entries.size)
    }

    @Test
    fun `clear removes all entries`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000,
            totalBalance = "100", currency = "CNY"
        ))
        RefreshLogStore.clear(context)
        assertTrue(RefreshLogStore.getEntries(context).isEmpty())
    }

    @Test
    fun `getEntries returns empty when store is empty`() {
        assertTrue(RefreshLogStore.getEntries(context).isEmpty())
    }

    @Test
    fun `miss log entry is stored correctly`() {
        val entry = RefreshLogEntry(
            id = 100, type = RefreshLogType.MISSED, timestamp = 5000,
            message = "预定刷新未触发", intervalSeconds = 30,
            expectedTime = 4000, alarmMethod = "exact",
            missReason = "系统资源紧张"
        )
        RefreshLogStore.addEntry(context, entry)
        val entries = RefreshLogStore.getEntries(context)
        assertEquals(1, entries.size)
        assertEquals(RefreshLogType.MISSED, entries[0].type)
        assertEquals("预定刷新未触发", entries[0].message)
        assertEquals("系统资源紧张", entries[0].missReason)
    }

    @Test
    fun `multiple entry types coexist`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "manual"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000, message = "auto"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 3, type = RefreshLogType.MISSED, timestamp = 3000, message = "missed"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 4, type = RefreshLogType.SERVICE_DIED, timestamp = 4000, message = "died"
        ))

        val entries = RefreshLogStore.getEntries(context)
        assertEquals(4, entries.size)
    }
}
