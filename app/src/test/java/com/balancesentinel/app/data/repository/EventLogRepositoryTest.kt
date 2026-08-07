package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventLogRepositoryTest {
    private lateinit var database: WalletDatabase
    private lateinit var repository: EventLogRepository

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
        runBlocking { database.accountDao().insertCreate(testAccount("event-repository-account")) }
        repository = RoomEventLogRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `newest logs are sorted newest first and respect limit`() = runTest {
        repository.append(
            listOf(
                RefreshLogEntry(1, RefreshLogType.AUTO, timestamp = 10),
                RefreshLogEntry(2, RefreshLogType.MANUAL, timestamp = 30),
                RefreshLogEntry(3, RefreshLogType.WATCHDOG, timestamp = 30)
            )
        )
        assertEquals(listOf(3L, 2L), repository.newest(2).map { it.id })
    }
}
