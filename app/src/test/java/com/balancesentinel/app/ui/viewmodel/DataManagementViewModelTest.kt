package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.api.balance.ScriptInspection
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.AppConfig
import com.balancesentinel.app.data.repository.BackupImportPlanner
import com.balancesentinel.app.data.repository.ConfigSettings
import com.balancesentinel.app.data.repository.ImportMode
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.UsageDataStore
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.WidgetConfigStore
import com.balancesentinel.app.widget.WidgetErrorLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    @Test
    fun `configuration preview waits for repository flow and uses its latest accounts`() = runTest {
        // Mutation caught: preview reading ApiKeyManager synchronously instead of the injected account Flow.
        val accountStorage = context.getSharedPreferences("flow-preview-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val source = RecordingAccountUiRepository(AccountLoadState.Loading)
        val planner = BackupImportPlanner(manager, prefs, ::staticInspection)
        val viewModel = DataManagementViewModel(app, manager, prefs, planner, source)
        val local = AccountInfo(LOCAL_ID, "Repository local", LOCAL_KEY)

        assertEquals(AccountLoadState.Loading, viewModel.uiState.value.accountLoadState)
        source.publish(AccountLoadState.Ready(listOf(local)))
        val previewed = viewModel.previewConfiguration(
            importConfig(false, listOf(local.copy(label = "Updated", apiKey = "")))
        )

        assertTrue(previewed)
        assertEquals(AccountLoadState.Ready(listOf(local)), viewModel.uiState.value.accountLoadState)
        assertEquals(1, viewModel.uiState.value.pendingImportPlan?.matchedUpdatedCount)
        assertEquals(1, source.subscriptionCount)
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `corrupt account flow blocks configuration preview and apply`() = runTest {
        // Mutation caught: a corrupt repository state being treated as an ordinary empty/current account list.
        val accountStorage = context.getSharedPreferences("corrupt-preview-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val local = AccountInfo(LOCAL_ID, "Local", LOCAL_KEY)
        manager.replaceAll(listOf(local))
        val corruption = DataCorruptionException("account payload corrupt")
        val source = RecordingAccountUiRepository(AccountLoadState.Corrupt(corruption))
        val viewModel = DataManagementViewModel(
            app,
            manager,
            prefs,
            BackupImportPlanner(manager, prefs, ::staticInspection),
            source
        )

        val previewed = viewModel.previewConfiguration(
            importConfig(true, listOf(local.copy(label = "Updated")))
        )

        assertFalse(previewed)
        val loadState = viewModel.uiState.value.accountLoadState
        assertTrue("Corruption must remain typed in DataManagementUiState", loadState is AccountLoadState.Corrupt)
        assertSame(corruption, (loadState as AccountLoadState.Corrupt).error)
        assertTrue(viewModel.uiState.value.importError)
        assertNull(viewModel.uiState.value.pendingImportPlan)
        assertFalse(viewModel.requestApplyImport())
        assertEquals(listOf(local), manager.getAccounts())
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `recreated data management view model subscribes to account repository again`() {
        // Mutation caught: retaining a legacy snapshot across page recreation.
        val accountStorage = context.getSharedPreferences("recreate-preview-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val source = RecordingAccountUiRepository(
            AccountLoadState.Ready(listOf(AccountInfo(LOCAL_ID, "Local", LOCAL_KEY)))
        )
        val planner = BackupImportPlanner(manager, prefs, ::staticInspection)

        DataManagementViewModel(app, manager, prefs, planner, source)
        DataManagementViewModel(app, manager, prefs, planner, source)
        ShadowLooper.idleMainLooper()

        assertEquals(2, source.subscriptionCount)
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `selecting a configuration file creates preview statistics with zero persistence`() = runTest {
        // Mutation caught: applying accounts or settings in the file-picker callback before preview confirmation.
        val accountStorage = context.getSharedPreferences("preview-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val local = AccountInfo(LOCAL_ID, "Local", LOCAL_KEY)
        manager.replaceAll(listOf(local))
        prefs.refreshIntervalSeconds = 222
        val incomingMatch = local.copy(label = "Updated", apiKey = "")
        val incomingUnmatched = AccountInfo(NEW_ID, "Skipped", "")
        val config = importConfig(
            credentialsIncluded = false,
            accounts = listOf(incomingMatch, incomingUnmatched),
            settings = importSettings(refreshIntervalSeconds = 77)
        )
        val file = java.io.File(context.filesDir, "preview-${System.nanoTime()}.json")
        file.writeText(Json { encodeDefaults = true }.encodeToString(config))
        val viewModel = DataManagementViewModel(
            app,
            manager,
            prefs,
            BackupImportPlanner(manager, prefs, ::staticInspection)
        )

        viewModel.previewConfiguration(Uri.fromFile(file))

        assertEquals(listOf(local), manager.getAccounts())
        assertEquals(222, prefs.refreshIntervalSeconds)
        val plan = viewModel.uiState.value.pendingImportPlan
        assertNotNull(plan)
        assertEquals(1, plan!!.matchedUpdatedCount)
        assertEquals(1, plan.retainedCredentialCount)
        assertEquals(1, plan.skippedCount)
        assertEquals(0, plan.createdCount)
        assertEquals(0, plan.deletedCount)
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `merge preview persists only after explicit apply`() = runTest {
        // Mutation caught: a preview that cannot be applied, or apply that does not commit the pending plan.
        val accountStorage = context.getSharedPreferences("merge-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val local = AccountInfo(LOCAL_ID, "Local", LOCAL_KEY)
        val created = AccountInfo(NEW_ID, "Created", NEW_KEY)
        manager.replaceAll(listOf(local))
        val viewModel = DataManagementViewModel(
            app,
            manager,
            prefs,
            BackupImportPlanner(manager, prefs, ::staticInspection)
        )
        viewModel.previewConfiguration(importConfig(true, listOf(created), importSettings(77)))

        assertEquals(listOf(local), manager.getAccounts())
        assertTrue(viewModel.requestApplyImport())
        assertEquals(listOf(local, created.copy(usageScriptEnabled = false)), manager.getAccounts())
        assertEquals(77, prefs.refreshIntervalSeconds)
        assertNull(viewModel.uiState.value.pendingImportPlan)
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `replace apply requires preview action then destructive confirmation with deletion count`() = runTest {
        // Mutation caught: replacing local accounts after the first apply action or losing the preview deletion count.
        val accountStorage = context.getSharedPreferences("replace-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val local = AccountInfo(OLD_ID, "Old", OLD_KEY)
        val replacement = AccountInfo(NEW_ID, "New", NEW_KEY)
        manager.replaceAll(listOf(local))
        val viewModel = DataManagementViewModel(
            app,
            manager,
            prefs,
            BackupImportPlanner(manager, prefs, ::staticInspection)
        )
        viewModel.previewConfiguration(importConfig(true, listOf(replacement)))
        viewModel.selectImportMode(ImportMode.REPLACE_ALL)

        assertEquals(ImportMode.REPLACE_ALL, viewModel.uiState.value.importMode)
        assertEquals(1, viewModel.uiState.value.pendingImportPlan?.deletedCount)
        assertFalse(viewModel.requestApplyImport())
        assertTrue(viewModel.uiState.value.replaceConfirmationRequired)
        assertEquals(listOf(local), manager.getAccounts())

        assertTrue(viewModel.confirmReplaceImport())
        assertEquals(listOf(replacement.copy(usageScriptEnabled = false)), manager.getAccounts())
        assertFalse(viewModel.uiState.value.replaceConfirmationRequired)
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `sanitized replace exposes blocking reasons and cannot request confirmation`() = runTest {
        // Mutation caught: surfacing an enabled destructive action for a credential-free backup.
        val accountStorage = context.getSharedPreferences("blocked-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        manager.replaceAll(listOf(AccountInfo(LOCAL_ID, "Local", LOCAL_KEY)))
        val viewModel = DataManagementViewModel(
            app,
            manager,
            prefs,
            BackupImportPlanner(manager, prefs, ::staticInspection)
        )
        viewModel.previewConfiguration(importConfig(false, emptyList()))
        viewModel.selectImportMode(ImportMode.REPLACE_ALL)

        val plan = viewModel.uiState.value.pendingImportPlan
        assertNotNull(plan)
        assertFalse(plan!!.canApply)
        assertTrue(plan.blockingReasons.isNotEmpty())
        assertFalse(viewModel.requestApplyImport())
        assertFalse(viewModel.uiState.value.replaceConfirmationRequired)
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `canonical origin checkboxes gate imported script enablement`() = runTest {
        // Mutation caught: enabling imported script code without its inspected canonical origin grant.
        val accountStorage = context.getSharedPreferences("origins-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, accountStorage)
        val prefs = WidgetPrefs(context)
        val origin = WebOrigin.https("Usage.Example.com")
        val scripted = AccountInfo(
            id = SCRIPT_ID,
            label = "Scripted",
            apiKey = SCRIPT_KEY,
            usageScript = SCRIPT
        )
        val planner = BackupImportPlanner(manager, prefs) { _, _ ->
            ScriptInspection(null, setOf(origin), staticallyDeterminable = true)
        }
        val viewModel = DataManagementViewModel(app, manager, prefs, planner)
        viewModel.previewConfiguration(importConfig(true, listOf(scripted)))

        assertEquals(1, viewModel.uiState.value.pendingImportPlan?.scriptAuthorizations?.size)
        viewModel.setImportedScriptEnabled(SCRIPT_ID, true)
        assertFalse(viewModel.uiState.value.pendingImportPlan!!.finalAccounts[0].usageScriptEnabled)
        viewModel.setImportOriginAuthorized(SCRIPT_ID, origin, true)
        assertTrue(viewModel.uiState.value.pendingImportPlan!!.finalAccounts[0].usageScriptEnabled)
        assertEquals(
            setOf("https://usage.example.com"),
            viewModel.uiState.value.pendingImportPlan!!.finalAccounts[0].authorizedScriptOrigins
        )
        accountStorage.edit().clear().commit()
    }

    private fun importConfig(
        credentialsIncluded: Boolean,
        accounts: List<AccountInfo>,
        settings: ConfigSettings = importSettings()
    ) = AppConfig(
        version = 2,
        credentialsIncluded = credentialsIncluded,
        exportedAt = "2026-08-01T00:00:00",
        appVersion = "2.0",
        accounts = accounts,
        settings = settings
    )

    private fun importSettings(refreshIntervalSeconds: Int = 30) = ConfigSettings(
        refreshIntervalSeconds = refreshIntervalSeconds,
        alertEnabled = false,
        alertThreshold = 0f,
        changeAlertEnabled = false,
        changeAlertThreshold = 0f,
        changeAlertPeriodMinutes = 60,
        logMaxEntries = 100
    )

    private suspend fun staticInspection(
        script: UsageScript,
        account: AccountInfo
    ): ScriptInspection = ScriptInspection(null, emptySet(), staticallyDeterminable = true)

    private class RecordingAccountUiRepository(
        initial: AccountLoadState
    ) : AccountUiRepository {
        private val states = MutableStateFlow(initial)
        var subscriptionCount: Int = 0
            private set

        override fun observe(): Flow<AccountLoadState> = flow {
            subscriptionCount++
            emitAll(states)
        }

        fun publish(state: AccountLoadState) {
            states.value = state
        }
    }

    private companion object {
        const val LOCAL_KEY = "sk-local-secret"
        const val LOCAL_ID = "96ed403d28356eeb"
        const val NEW_KEY = "sk-new-complete"
        const val NEW_ID = "7c6888f7ec01a4e6"
        const val OLD_KEY = "sk-old-complete"
        const val OLD_ID = "41afefea72a24e69"
        const val SCRIPT_KEY = "sk-script-account"
        const val SCRIPT_ID = "6bbdfb3957422e13"
        const val SCRIPT = "({ request: { url: 'https://usage.example.com/balance' } })"
    }
}
