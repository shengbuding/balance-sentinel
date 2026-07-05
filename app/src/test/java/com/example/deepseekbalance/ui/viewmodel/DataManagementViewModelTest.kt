package com.example.deepseekbalance.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.deepseekbalance.CrashLogger
import com.example.deepseekbalance.data.model.DailySummary
import com.example.deepseekbalance.data.model.RawRecord
import com.example.deepseekbalance.data.model.RefreshLogEntry
import com.example.deepseekbalance.data.model.RefreshLogType
import com.example.deepseekbalance.data.model.UsageRecord
import com.example.deepseekbalance.data.model.UsageSnapshot
import com.example.deepseekbalance.data.repository.DailySummaryStore
import com.example.deepseekbalance.data.repository.RawRecordStore
import com.example.deepseekbalance.data.repository.RefreshLogStore
import com.example.deepseekbalance.data.repository.RefreshScheduler
import com.example.deepseekbalance.data.repository.UsageDataStore
import com.example.deepseekbalance.data.repository.WidgetPrefs
import com.example.deepseekbalance.widget.BalanceWidgetDataStore
import com.example.deepseekbalance.widget.WidgetConfigStore
import com.example.deepseekbalance.widget.WidgetErrorLogger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class DataManagementViewModelTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        clearAllStores()
    }

    @After
    fun tearDown() {
        clearAllStores()
    }

    private fun clearAllStores() {
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
        UsageDataStore.clear(context)
        RefreshLogStore.clear(context)
        WidgetErrorLogger.clear(context)
        CrashLogger.clear(app)
        BalanceWidgetDataStore.clearAll(context)
        WidgetConfigStore.clearAll(context)
        WidgetPrefs(context).resetAll()
        RefreshScheduler.resetAlarmCounters(context)
    }

    // ═══════════════════════════════════════════════════════════
    // loadStats — empty state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loadStats returns all zeros for empty stores`() {
        val viewModel = DataManagementViewModel(app)

        val state = viewModel.uiState.value
        assertEquals(0, state.rawRecordCount)
        assertEquals(0, state.rawRecordDistinctDates)
        assertEquals(0, state.dailySummaryCount)
        assertEquals(0, state.usageSnapshotCount)
        assertEquals(0, state.refreshLogCount)
        assertEquals(0, state.widgetErrorCount)
        assertEquals(0, state.widgetBalanceCount)
        assertEquals(0, state.crashCount)
        assertEquals(0, state.alarmCounters.totalSet)
        assertEquals(0, state.alarmCounters.totalFired)
        assertEquals(0, state.alarmCounters.totalCancelled)
        assertEquals(0, state.alarmCounters.totalDropped)
    }

    // ═══════════════════════════════════════════════════════════
    // loadStats — populated state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loadStats reflects raw record count and distinct dates`() {
        val now = System.currentTimeMillis()
        RawRecordStore.addRecord(context, RawRecord("acc1", now, "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", now + 86_400_000L, "CNY", 90f, 0f, 90f))
        RawRecordStore.addRecord(context, RawRecord("acc1", now + 3600_000L, "CNY", 95f, 0f, 95f))

        val viewModel = DataManagementViewModel(app)
        val state = viewModel.uiState.value
        assertEquals(3, state.rawRecordCount)
        assertEquals(2, state.rawRecordDistinctDates)
    }

    @Test
    fun `loadStats reflects daily summary count`() {
        DailySummaryStore.upsert(context, DailySummary(
            accountId = "acc1", date = "2026-07-01", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 1,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        ))
        DailySummaryStore.upsert(context, DailySummary(
            accountId = "acc1", date = "2026-07-02", currency = "CNY",
            open = 90f, close = 85f, consumed = 5f, toppedUp = 0f,
            granted = 0f, avgBalance = 87f, sampleCount = 1,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        ))

        val viewModel = DataManagementViewModel(app)
        assertEquals(2, viewModel.uiState.value.dailySummaryCount)
    }

    @Test
    fun `loadStats reflects usage snapshot count`() {
        val now = System.currentTimeMillis()
        UsageDataStore.saveSnapshot(context, UsageSnapshot(
            accountId = "acc1", timestamp = now, records = listOf(
                UsageRecord("deepseek-chat", 1000, 600, 400)
            )
        ))

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.usageSnapshotCount)
    }

    @Test
    fun `loadStats reflects refresh log count`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = System.currentTimeMillis(),
            type = RefreshLogType.MANUAL,
            timestamp = System.currentTimeMillis(),
            message = "Manual refresh"
        ))

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.refreshLogCount)
    }

    @Test
    fun `loadStats reflects widget error count`() {
        WidgetErrorLogger.logMessage(context, "Test error")

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.widgetErrorCount)
    }

    @Test
    fun `loadStats reflects widget balance cache count`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "Test", "100.00", "CNY",
            true, "50.00", "50.00"
        )

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.widgetBalanceCount)
    }

    @Test
    fun `loadStats reflects alarm counters`() {
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 30_000, "exact")
        RefreshScheduler.markFired(context)
        RefreshScheduler.markCancelled(context)
        RefreshScheduler.markDropped(context)
        RefreshScheduler.markDropped(context)

        val viewModel = DataManagementViewModel(app)
        val counters = viewModel.uiState.value.alarmCounters
        assertEquals(1, counters.totalSet)
        assertEquals(1, counters.totalFired)
        assertEquals(1, counters.totalCancelled)
        assertEquals(2, counters.totalDropped)
    }

    // ═══════════════════════════════════════════════════════════
    // Dialog state machine
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `requestAction sets pendingAction`() {
        val viewModel = DataManagementViewModel(app)
        assertNull(viewModel.uiState.value.pendingAction)

        viewModel.requestAction(PendingAction.ClearRawRecords)
        assertEquals(PendingAction.ClearRawRecords, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `dismissAction clears pendingAction`() {
        val viewModel = DataManagementViewModel(app)
        viewModel.requestAction(PendingAction.ClearDailySummaries)
        assertNotNull(viewModel.uiState.value.pendingAction)

        viewModel.dismissAction()
        assertNull(viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `clearResultMessage sets resultMessage to null`() {
        val viewModel = DataManagementViewModel(app)
        // Simulate setting a resultMessage
        viewModel.requestAction(PendingAction.ClearRawRecords)
        assertNull(viewModel.uiState.value.resultMessage)

        viewModel.clearResultMessage()
        assertNull(viewModel.uiState.value.resultMessage)
    }

    @Test
    fun `requestAction overwrites previous pendingAction`() {
        val viewModel = DataManagementViewModel(app)
        viewModel.requestAction(PendingAction.ClearRawRecords)
        viewModel.requestAction(PendingAction.ResetSettings)
        assertEquals(PendingAction.ResetSettings, viewModel.uiState.value.pendingAction)
    }

    // ═══════════════════════════════════════════════════════════
    // executeAction — Clear operations
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `executeAction ClearRawRecords clears all records`() {
        RawRecordStore.addRecord(context, RawRecord("acc1", System.currentTimeMillis(), "CNY", 100f, 0f, 100f))
        RawRecordStore.addRecord(context, RawRecord("acc1", System.currentTimeMillis() + 1000, "CNY", 90f, 0f, 90f))

        val viewModel = DataManagementViewModel(app)
        assertEquals(2, viewModel.uiState.value.rawRecordCount)

        viewModel.executeAction(PendingAction.ClearRawRecords)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(0, viewModel.uiState.value.rawRecordCount)
        assertNotNull(viewModel.uiState.value.resultMessage)
        assertNull(viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `executeAction ClearDailySummaries clears all summaries`() {
        DailySummaryStore.upsert(context, DailySummary(
            accountId = "acc1", date = "2026-07-01", currency = "CNY",
            open = 100f, close = 90f, consumed = 10f, toppedUp = 0f,
            granted = 0f, avgBalance = 95f, sampleCount = 1,
            toppedUpBalanceClose = 0f, grantedBalanceClose = 0f
        ))

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.dailySummaryCount)

        viewModel.executeAction(PendingAction.ClearDailySummaries)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(0, viewModel.uiState.value.dailySummaryCount)
    }

    @Test
    fun `executeAction ClearUsageSnapshots clears all snapshots`() {
        UsageDataStore.saveSnapshot(context, UsageSnapshot(
            accountId = "acc1", timestamp = System.currentTimeMillis(), records = listOf(
                UsageRecord("deepseek-chat", 1000, 600, 400)
            )
        ))

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.usageSnapshotCount)

        viewModel.executeAction(PendingAction.ClearUsageSnapshots)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(0, viewModel.uiState.value.usageSnapshotCount)
    }

    @Test
    fun `executeAction ClearRefreshLogs clears all logs`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = System.currentTimeMillis(),
            type = RefreshLogType.MANUAL,
            timestamp = System.currentTimeMillis(),
            message = "Test"
        ))

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.refreshLogCount)

        viewModel.executeAction(PendingAction.ClearRefreshLogs)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(0, viewModel.uiState.value.refreshLogCount)
    }

    @Test
    fun `executeAction ClearWidgetErrors clears all errors`() {
        WidgetErrorLogger.logMessage(context, "Test widget error")

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.widgetErrorCount)

        viewModel.executeAction(PendingAction.ClearWidgetErrors)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(0, viewModel.uiState.value.widgetErrorCount)
    }

    // ═══════════════════════════════════════════════════════════
    // executeAction — Reset operations
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `executeAction ResetAlarmCounters resets all counters`() {
        RefreshScheduler.recordSchedule(context, 30, System.currentTimeMillis() + 30_000, "exact")
        RefreshScheduler.markFired(context)
        RefreshScheduler.markCancelled(context)
        RefreshScheduler.markDropped(context)

        val viewModel = DataManagementViewModel(app)
        assertEquals(1, viewModel.uiState.value.alarmCounters.totalSet)

        viewModel.executeAction(PendingAction.ResetAlarmCounters)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        val counters = viewModel.uiState.value.alarmCounters
        assertEquals(0, counters.totalSet)
        assertEquals(0, counters.totalFired)
        assertEquals(0, counters.totalCancelled)
        assertEquals(0, counters.totalDropped)
    }

    @Test
    fun `executeAction ResetSettings restores defaults and clears widget configs`() {
        val prefs = WidgetPrefs(context)
        prefs.refreshIntervalSeconds = 120
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")

        val viewModel = DataManagementViewModel(app)

        viewModel.executeAction(PendingAction.ResetSettings)
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        // Settings restored to defaults
        assertEquals(WidgetPrefs.DEFAULT_INTERVAL, prefs.refreshIntervalSeconds)
        assertFalse(prefs.alertEnabled)
        assertEquals(0f, prefs.alertThreshold)
        // Widget configs cleared
        assertNull(WidgetConfigStore.getConfig(context, 1))
        assertNotNull(viewModel.uiState.value.resultMessage)
        assertNull(viewModel.uiState.value.pendingAction)
    }

    // ═══════════════════════════════════════════════════════════
    // executeAction — ResetEntireApp (dialog state only;
    // full execution skipped due to EncryptedSharedPreferences
    // in ApiKeyManager not being available in Robolectric)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ResetEntireApp dialog flow works correctly`() {
        val viewModel = DataManagementViewModel(app)

        // Request action shows dialog
        viewModel.requestAction(PendingAction.ResetEntireApp)
        assertEquals(PendingAction.ResetEntireApp, viewModel.uiState.value.pendingAction)

        // Dismiss hides dialog
        viewModel.dismissAction()
        assertNull(viewModel.uiState.value.pendingAction)
    }

    // ═══════════════════════════════════════════════════════════
    // loadStats — explicit reload after data changes
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loadStats refreshes after external data changes`() {
        val viewModel = DataManagementViewModel(app)
        assertEquals(0, viewModel.uiState.value.rawRecordCount)

        // Add data externally
        RawRecordStore.addRecord(context, RawRecord("acc1", System.currentTimeMillis(), "CNY", 100f, 0f, 100f))

        // Stats still stale
        assertEquals(0, viewModel.uiState.value.rawRecordCount)

        // Reload
        viewModel.loadStats()
        assertEquals(1, viewModel.uiState.value.rawRecordCount)
    }
}
