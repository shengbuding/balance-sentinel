package com.example.deepseekbalance.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.deepseekbalance.CrashLogger
import com.example.deepseekbalance.R
import com.example.deepseekbalance.data.repository.ApiKeyManager
import com.example.deepseekbalance.data.repository.DailySummaryStore
import com.example.deepseekbalance.data.repository.RawRecordStore
import com.example.deepseekbalance.data.repository.RefreshLogStore
import com.example.deepseekbalance.data.repository.RefreshScheduler
import com.example.deepseekbalance.data.repository.UsageDataStore
import com.example.deepseekbalance.data.repository.WidgetPrefs
import com.example.deepseekbalance.widget.BalanceWidgetDataStore
import com.example.deepseekbalance.widget.WidgetConfigStore
import com.example.deepseekbalance.widget.WidgetErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════
// UI State
// ═══════════════════════════════════════════════════════════

data class DataManagementUiState(
    // 存储统计
    val rawRecordCount: Int = 0,
    val rawRecordDistinctDates: Int = 0,
    val dailySummaryCount: Int = 0,
    val usageSnapshotCount: Int = 0,
    val refreshLogCount: Int = 0,
    val widgetErrorCount: Int = 0,
    val widgetBalanceCount: Int = 0,
    val alarmCounters: AlarmCounterSnapshot = AlarmCounterSnapshot(),
    val crashCount: Int = 0,

    // 对话框控制
    val pendingAction: PendingAction? = null,
    // 操作结果 Toast 消息
    val resultMessage: String? = null
)

data class AlarmCounterSnapshot(
    val totalSet: Int = 0,
    val totalFired: Int = 0,
    val totalCancelled: Int = 0,
    val totalDropped: Int = 0
)

/** 需要用户确认的破坏性操作。 */
sealed class PendingAction {
    data object ClearRawRecords : PendingAction()
    data object ClearDailySummaries : PendingAction()
    data object ClearUsageSnapshots : PendingAction()
    data object ClearRefreshLogs : PendingAction()
    data object ClearWidgetErrors : PendingAction()
    data object ResetAlarmCounters : PendingAction()
    data object ResetSettings : PendingAction()
    data object ResetEntireApp : PendingAction()
}

// ═══════════════════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════════════════

class DataManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DataManagementUiState())
    val uiState: StateFlow<DataManagementUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    // ── 统计 ──

    fun loadStats() {
        val ctx = getApplication<Application>()
        val raw = RawRecordStore.getAllRecords(ctx)
        val summaries = DailySummaryStore.getSummaries(ctx)
        val snapshots = UsageDataStore.getAllSnapshots(ctx)
        val logs = RefreshLogStore.getEntries(ctx)
        val widgetErrors = WidgetErrorLogger.getLogs(ctx)
        val balances = BalanceWidgetDataStore.getAllBalances(ctx)
        val state = RefreshScheduler.getState(ctx)
        val crashes = CrashLogger.getCrashes(ctx)

        _uiState.value = DataManagementUiState(
            rawRecordCount = raw.size,
            rawRecordDistinctDates = RawRecordStore.getDistinctDates(ctx).size,
            dailySummaryCount = summaries.size,
            usageSnapshotCount = snapshots.size,
            refreshLogCount = logs.size,
            widgetErrorCount = widgetErrors.size,
            widgetBalanceCount = balances.size,
            alarmCounters = AlarmCounterSnapshot(
                totalSet = state.totalAlarmsSet,
                totalFired = state.totalAlarmsFired,
                totalCancelled = state.totalCancelled,
                totalDropped = state.totalDropped
            ),
            crashCount = crashes.size
        )
    }

    // ── 对话框 ──

    fun requestAction(action: PendingAction) {
        _uiState.value = _uiState.value.copy(pendingAction = action)
    }

    fun dismissAction() {
        _uiState.value = _uiState.value.copy(pendingAction = null)
    }

    fun clearResultMessage() {
        _uiState.value = _uiState.value.copy(resultMessage = null)
    }

    // ── 执行 ──

    fun executeAction(action: PendingAction) {
        val ctx = getApplication<Application>()
        // 在 IO 线程执行存储操作
        CoroutineScope(Dispatchers.IO).launch {
            val res = ctx.resources
            val message: String = when (action) {
                PendingAction.ClearRawRecords -> {
                    RawRecordStore.clear(ctx)
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearDailySummaries -> {
                    DailySummaryStore.clear(ctx)
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearUsageSnapshots -> {
                    UsageDataStore.clear(ctx)
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearRefreshLogs -> {
                    RefreshLogStore.clear(ctx)
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearWidgetErrors -> {
                    WidgetErrorLogger.clear(ctx)
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ResetAlarmCounters -> {
                    RefreshScheduler.resetAlarmCounters(ctx)
                    res.getString(R.string.data_reset_toast)
                }
                PendingAction.ResetSettings -> {
                    WidgetPrefs(ctx).resetAll()
                    WidgetConfigStore.clearAll(ctx)
                    res.getString(R.string.data_reset_toast)
                }
                PendingAction.ResetEntireApp -> {
                    RawRecordStore.clear(ctx)
                    DailySummaryStore.clear(ctx)
                    UsageDataStore.clear(ctx)
                    RefreshLogStore.clear(ctx)
                    WidgetErrorLogger.clear(ctx)
                    CrashLogger.clear(ctx)
                    BalanceWidgetDataStore.clearAll(ctx)
                    WidgetConfigStore.clearAll(ctx)
                    WidgetPrefs(ctx).resetAll()
                    RefreshScheduler.resetAlarmCounters(ctx)
                    ApiKeyManager(ctx).clearAll()
                    res.getString(R.string.data_reset_app_toast)
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(pendingAction = null)
                loadStats()
                _uiState.value = _uiState.value.copy(resultMessage = message)
            }
        }
    }
}
