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

    @Test
    fun `append trims Room logs to the published maximum`() = runTest {
        database.appSettingsDao().upsert(
            backgroundRefreshIntervalSeconds = 900,
            foregroundMonitoringIntervalSeconds = 30,
            alertEnabled = false,
            alertThreshold = 0.0,
            changeAlertEnabled = false,
            changeAlertThreshold = 0.0,
            changeAlertPeriodMinutes = 0,
            logMaxEntries = 10,
            snoozeDurationMinutes = 60,
            showTotalBalanceInNotification = true,
            updatedAt = 1L
        )
        repository.append(
            (1L..11L).map { id ->
                RefreshLogEntry(id, RefreshLogType.MANUAL, timestamp = id)
            }
        )

        assertEquals(10L, database.eventLogDao().countLogs())
        assertEquals((11L downTo 2L).toList(), repository.newest(20).map { it.id })
    }
}
