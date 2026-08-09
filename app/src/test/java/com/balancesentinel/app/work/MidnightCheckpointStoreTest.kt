package com.balancesentinel.app.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MidnightCheckpointStoreTest {
    private lateinit var database: WalletDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        WalletDatabaseProvider.installForTests(database)
    }

    @After
    fun tearDown() {
        WalletDatabaseProvider.clearForTests()
    }

    @Test
    fun `checkpoint advances once and rejects duplicate or older dates`() = runBlocking {
        val store = RoomMaintenanceCheckpointStore(context)
        val zone = ZoneId.of("America/New_York")

        assertTrue(store.markCompleted(LocalDate.of(2026, 8, 7), zone, 1L))
        assertFalse(store.markCompleted(LocalDate.of(2026, 8, 7), zone, 2L))
        assertFalse(store.markCompleted(LocalDate.of(2026, 8, 6), zone, 3L))

        val current = store.read(zone)
        assertEquals(LocalDate.of(2026, 8, 7), current.lastCompletedDate)
        assertEquals(zone, current.zoneId)
        assertEquals(1L, current.lastSuccessAt)
    }

    @Test
    fun `zone change can atomically rebase to an older local date`() = runBlocking {
        val store = RoomMaintenanceCheckpointStore(context)
        val utc = ZoneId.of("UTC")
        val newZone = ZoneId.of("America/Los_Angeles")

        assertTrue(store.markCompleted(LocalDate.of(2026, 8, 10), utc, 1L))
        assertTrue(store.markCompleted(LocalDate.of(2026, 8, 9), newZone, 2L))

        val current = store.read(newZone)
        assertEquals(LocalDate.of(2026, 8, 9), current.lastCompletedDate)
        assertEquals(newZone, current.zoneId)
        assertEquals(2L, current.lastSuccessAt)
    }
}
