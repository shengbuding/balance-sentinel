package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.R
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.AppConfig
import com.balancesentinel.app.data.repository.BackupImportPlan
import com.balancesentinel.app.data.repository.BackupImportPlanner
import com.balancesentinel.app.data.repository.ConfigManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.UsageDataStore
import com.balancesentinel.app.data.repository.ImportMode
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.WidgetConfigStore
import com.balancesentinel.app.widget.WidgetErrorLogger
import androidx.lifecycle.viewModelScope
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
    val resultMessage: String? = null,
    val pendingImportPlan: BackupImportPlan? = null,
    val importMode: ImportMode = ImportMode.MERGE,
    val enabledImportedScripts: Set<String> = emptySet(),
    val authorizedImportOrigins: Map<String, Set<WebOrigin>> = emptyMap(),
    val replaceConfirmationRequired: Boolean = false,
    val importError: Boolean = false
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
    data object ClearConsoleData : PendingAction()
    data object ResetAlarmCounters : PendingAction()
    data object ResetSettings : PendingAction()
    data object ResetEntireApp : PendingAction()
}

// ═══════════════════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════════════════

class DataManagementViewModel @JvmOverloads constructor(
    application: Application,
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(application),
    private val widgetPrefs: WidgetPrefs = WidgetPrefs(application),
    private val importPlanner: BackupImportPlanner = BackupImportPlanner(apiKeyManager, widgetPrefs),
    private val accountUiRepository: AccountUiRepository? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DataManagementUiState())
    val uiState: StateFlow<DataManagementUiState> = _uiState.asStateFlow()
    private var pendingImportConfig: AppConfig? = null

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

        _uiState.value = _uiState.value.copy(
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

    suspend fun previewConfiguration(uri: Uri): Boolean {
        val config = withContext(Dispatchers.IO) {
            ConfigManager.importFromUri(getApplication(), uri)
        }
        if (config == null) {
            pendingImportConfig = null
            _uiState.value = _uiState.value.copy(
                pendingImportPlan = null,
                replaceConfirmationRequired = false,
                importError = true
            )
            return false
        }
        return previewConfiguration(config)
    }

    suspend fun previewConfiguration(config: AppConfig): Boolean {
        val plan = importPlanner.plan(config, apiKeyManager.getAccounts(), ImportMode.MERGE)
        pendingImportConfig = config
        _uiState.value = _uiState.value.copy(
            pendingImportPlan = plan,
            importMode = ImportMode.MERGE,
            enabledImportedScripts = emptySet(),
            authorizedImportOrigins = emptyMap(),
            replaceConfirmationRequired = false,
            importError = false
        )
        return true
    }

    suspend fun selectImportMode(mode: ImportMode) {
        val config = pendingImportConfig ?: return
        val basePlan = importPlanner.plan(config, apiKeyManager.getAccounts(), mode)
        val plan = importPlanner.withScriptAuthorizations(
            basePlan,
            _uiState.value.enabledImportedScripts,
            _uiState.value.authorizedImportOrigins
        )
        _uiState.value = _uiState.value.copy(
            pendingImportPlan = plan,
            importMode = mode,
            replaceConfirmationRequired = false,
            importError = false
        )
    }

    fun setImportedScriptEnabled(accountId: String, enabled: Boolean) {
        val plan = _uiState.value.pendingImportPlan ?: return
        val enabledAccounts = _uiState.value.enabledImportedScripts.toMutableSet().apply {
            if (enabled) add(accountId) else remove(accountId)
        }
        updateScriptAuthorizations(plan, enabledAccounts, _uiState.value.authorizedImportOrigins)
    }

    fun setImportOriginAuthorized(accountId: String, origin: WebOrigin, authorized: Boolean) {
        val plan = _uiState.value.pendingImportPlan ?: return
        val origins = _uiState.value.authorizedImportOrigins
            .mapValues { it.value.toMutableSet() }
            .toMutableMap()
        val accountOrigins = origins.getOrPut(accountId) { mutableSetOf() }
        if (authorized) accountOrigins.add(origin) else accountOrigins.remove(origin)
        if (accountOrigins.isEmpty()) origins.remove(accountId)
        updateScriptAuthorizations(
            plan,
            _uiState.value.enabledImportedScripts,
            origins.mapValues { it.value.toSet() }
        )
    }

    fun requestApplyImport(): Boolean {
        val plan = _uiState.value.pendingImportPlan ?: return false
        if (!plan.canApply) return false
        if (plan.mode == ImportMode.REPLACE_ALL) {
            _uiState.value = _uiState.value.copy(replaceConfirmationRequired = true)
            return false
        }
        return applyPendingImport(plan, confirmedFullReplace = false)
    }

    fun confirmReplaceImport(): Boolean {
        val plan = _uiState.value.pendingImportPlan ?: return false
        if (!_uiState.value.replaceConfirmationRequired || plan.mode != ImportMode.REPLACE_ALL) {
            return false
        }
        return applyPendingImport(plan, confirmedFullReplace = true)
    }

    fun dismissImportPreview() {
        pendingImportConfig = null
        _uiState.value = _uiState.value.copy(
            pendingImportPlan = null,
            importMode = ImportMode.MERGE,
            enabledImportedScripts = emptySet(),
            authorizedImportOrigins = emptyMap(),
            replaceConfirmationRequired = false,
            importError = false
        )
    }

    private fun updateScriptAuthorizations(
        plan: BackupImportPlan,
        enabledAccounts: Set<String>,
        origins: Map<String, Set<WebOrigin>>
    ) {
        _uiState.value = _uiState.value.copy(
            pendingImportPlan = importPlanner.withScriptAuthorizations(plan, enabledAccounts, origins),
            enabledImportedScripts = enabledAccounts,
            authorizedImportOrigins = origins,
            replaceConfirmationRequired = false
        )
    }

    private fun applyPendingImport(
        plan: BackupImportPlan,
        confirmedFullReplace: Boolean
    ): Boolean = try {
        importPlanner.apply(plan, confirmedFullReplace)
        pendingImportConfig = null
        _uiState.value = _uiState.value.copy(
            pendingImportPlan = null,
            importMode = ImportMode.MERGE,
            enabledImportedScripts = emptySet(),
            authorizedImportOrigins = emptyMap(),
            replaceConfirmationRequired = false,
            importError = false,
            resultMessage = getApplication<Application>().getString(
                R.string.data_config_import_success,
                plan.finalAccounts.size
            )
        )
        loadStats()
        true
    } catch (_: Exception) {
        _uiState.value = _uiState.value.copy(
            replaceConfirmationRequired = false,
            importError = true
        )
        false
    }

    // ── 执行 ──

    /**
     * 清除 WebView 数据
     */
    private fun clearWebViewData(ctx: android.content.Context) {
        try {
            // 清除 cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            // 清除 WebView 缓存
            android.webkit.WebStorage.getInstance().deleteAllData()

            // 清除本地存储目录
            val webViewDir = java.io.File(ctx.cacheDir, "WebView")
            if (webViewDir.exists()) {
                webViewDir.deleteRecursively()
            }

            // 清除应用缓存中的 WebView 数据
            val cacheDir = ctx.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.contains("WebView") || file.name.contains("webview")) {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            // 忽略错误
        }
    }

    fun executeAction(action: PendingAction) {
        val ctx = getApplication<Application>()
        // 在 IO 线程执行存储操作
        viewModelScope.launch(Dispatchers.IO) {
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
                PendingAction.ClearConsoleData -> {
                    // 只清除控制台存储数据
                    com.balancesentinel.app.data.console.store.ConsoleStore(ctx).clearAll()
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
                    // 清除控制台数据
                    com.balancesentinel.app.data.console.store.ConsoleStore(ctx).clearAll()
                    try {
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.removeAllCookies(null)
                        cookieManager.flush()
                    } catch (e: Exception) {
                        // 忽略错误
                    }
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
