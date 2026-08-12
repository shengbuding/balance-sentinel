package com.balancesentinel.app.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.WidgetErrorLogger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomDataStatisticsRepositoryTest {
    private lateinit var application: Application
    private lateinit var database: WalletDatabase
    private lateinit var repository: RoomDataStatisticsRepository

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = createWalletTestDatabase()
        repository = RoomDataStatisticsRepository(application, database)
        BalanceWidgetDataStore.clearAll(application)
        WidgetErrorLogger.clear(application)
        CrashLogger.clear(application)
        RefreshScheduler.resetAlarmCounters(application)
    }

    @After
    fun tearDown() {
        BalanceWidgetDataStore.clearAll(application)
        WidgetErrorLogger.clear(application)
        CrashLogger.clear(application)
        RefreshScheduler.resetAlarmCounters(application)
        database.close()
    }

    @Test
    fun `loadSummary reads aggregate counts without materializing stored rows`() = runTest {
        database.accountDao().insertCreate(testAccount("account"))
        database.historyDao().insertBalanceBatch(
            listOf(
                BalanceRecordEntity(
                    accountId = "account",
                    currency = "USD",
                    recordedAt = 1_786_291_200_000L,
                    totalBalance = 10.0
                ),
                BalanceRecordEntity(
                    accountId = "account",
                    currency = "USD",
                    recordedAt = 1_786_377_600_000L,
                    totalBalance = 9.0
                )
            )
        )
        database.historyDao().upsertSummaries(
            listOf(
                DailySummaryEntity(
                    date = "2026-08-10",
                    accountId = "account",
                    currency = "USD",
                    openBalance = 10.0,
                    closeBalance = 9.0,
                    consumedBalance = 1.0,
                    toppedUpBalance = 0.0,
                    averageBalance = 9.5,
                    sampleCount = 2,
                    generatedAt = 1_786_377_600_000L
                )
            )
        )
        database.usageDao().upsertSnapshot(
            UsageSnapshotEntity(
                id = "usage",
                accountId = "account",
                capturedAt = 1_786_377_600_000L
            )
        )
        database.eventLogDao().insertAll(
            listOf(
                EventLogEntity(
                    accountId = "account",
                    eventType = EventLogType.MANUAL,
                    recordedAt = 1_786_377_600_000L
                )
            )
        )
        WidgetErrorLogger.logMessage(application, "widget failure")
        BalanceWidgetDataStore.saveAccountBalance(
            application,
            accountId = "account",
            label = "Account",
            totalBalance = "9.00",
            currency = "USD",
            isAvailable = true,
            grantedBalance = "0.00",
            toppedUpBalance = "0.00"
        )
        RefreshScheduler.recordSchedule(application, 30, 1_786_377_630_000L, "test")
        RefreshScheduler.markFired(application)

        val actual = repository.loadSummary()

        assertEquals(2L, actual.rawRecordCount)
        assertEquals(2L, actual.rawRecordDistinctDates)
        assertEquals(1L, actual.dailySummaryCount)
        assertEquals(1L, actual.usageSnapshotCount)
        assertEquals(1L, actual.refreshLogCount)
        assertEquals(1, actual.widgetErrorCount)
        assertEquals(1, actual.widgetBalanceCount)
        assertEquals(1, actual.alarmCounters.totalSet)
        assertEquals(1, actual.alarmCounters.totalFired)
        assertEquals(0, actual.crashCount)
    }
}
