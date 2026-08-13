package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.R
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.AppResetCoordinator
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountMapper
import com.balancesentinel.app.data.repository.AccountLifecycleManager
import com.balancesentinel.app.data.repository.AccountMutationCoordinator
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.data.repository.AppConfig
import com.balancesentinel.app.data.repository.BackupImportPlan
import com.balancesentinel.app.data.repository.BackupImportPlanner
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.repository.ConfigManager
import com.balancesentinel.app.data.repository.DataExporter
import com.balancesentinel.app.data.repository.DataHistoryRepository
import com.balancesentinel.app.data.repository.DataStatisticsRepository
import com.balancesentinel.app.data.repository.RoomDataHistoryRepository
import com.balancesentinel.app.data.repository.RoomDataStatisticsRepository
import com.balancesentinel.app.data.repository.RoomConfigImportCoordinator
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.ImportMode
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.WidgetConfigStore
import com.balancesentinel.app.widget.WidgetErrorLogger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ═══════════════════════════════════════════════════════════
// UI State
// ═══════════════════════════════════════════════════════════

data class DataManagementUiState(
    val accountLoadState: AccountLoadState = AccountLoadState.Loading,
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
    val statisticsLoaded: Boolean = false,

    // 对话框控制
    val pendingAction: PendingAction? = null,
    // 操作结果 Toast 消息
    val resultMessage: String? = null,
    val pendingImportPlan: BackupImportPlan? = null,
    val importMode: ImportMode = ImportMode.MERGE,
    val enabledImportedScripts: Set<String> = emptySet(),
    val authorizedImportOrigins: Map<String, Set<WebOrigin>> = emptyMap(),
    val replaceConfirmationRequired: Boolean = false,
    val importError: Boolean = false,
    val importInProgress: Boolean = false,
    val dataOperationState: DataOperationState = DataOperationState.Idle
) {
    val historyOperationState: HistoryOperationState
        get() = when (dataOperationState) {
            DataOperationState.Idle -> HistoryOperationState.IDLE
            is DataOperationState.Running -> HistoryOperationState.ACTIVE
            is DataOperationState.Succeeded -> HistoryOperationState.SUCCEEDED
            is DataOperationState.Failed -> HistoryOperationState.FAILED
            is DataOperationState.Cancelled -> HistoryOperationState.CANCELLED
        }
}

enum class HistoryOperationState { IDLE, ACTIVE, SUCCEEDED, FAILED, CANCELLED }

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
    private val importPlanner: BackupImportPlanner = BackupImportPlanner(
        apiKeyManager,
        widgetPrefs,
        settingsRepository = SettingsRepositoryProvider.get(application),
        configImportCoordinator = RoomConfigImportCoordinator(
            WalletDatabaseProvider.get(application),
            EncryptedPreferencesCredentialStore(application),
            SettingsRepositoryProvider.get(application)
        )
    ),
    private val injectedAccountUiRepository: AccountUiRepository? = null,
    private val injectedAccountMutationCoordinator: AccountMutationCoordinator? = null,
    private val injectedDataStatisticsRepository: DataStatisticsRepository? = null,
    private val injectedDataHistoryRepository: DataHistoryRepository? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DataManagementUiState())
    val uiState: StateFlow<DataManagementUiState> = _uiState.asStateFlow()
    private var pendingImportConfig: AppConfig? = null
    private val importInProgressGuard = AtomicBoolean(false)
    private val accountSource: AccountUiRepository = injectedAccountUiRepository
        ?: RoomAccountUiRepository(
            RoomAccountRepository(WalletDatabaseProvider.get(application)),
            EncryptedPreferencesCredentialStore(application)
        )
    private val mutationCoordinator: AccountMutationCoordinator = injectedAccountMutationCoordinator
        ?: AccountLifecycleManager(application).mutationCoordinator()
    private val database = WalletDatabaseProvider.get(application)
    private val appResetCoordinator = AppResetCoordinator(
        application,
        database,
        EncryptedPreferencesCredentialStore(application),
        SettingsRepositoryProvider.get(application)
    )
    private val statisticsRepository = injectedDataStatisticsRepository
        ?: RoomDataStatisticsRepository(application, database)
    private val historyRepository = injectedDataHistoryRepository
        ?: RoomDataHistoryRepository(application)
    private var accountCollectionJob: Job? = null
    private var statisticsJob: Job? = null
    private var historyOperationJob: Job? = null
    private var historyOperationOwner: String? = null

    init {
        observeAccounts()
        loadStats()
    }

    private fun observeAccounts() {
        accountCollectionJob?.cancel()
        accountCollectionJob = viewModelScope.launch {
            accountSource.observe()
                .catch { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    emit(
                        AccountLoadState.Corrupt(
                            error as? DataCorruptionException
                                ?: DataCorruptionException("Account UI state cannot be read", error)
                        )
                    )
                }
                .collect { state ->
                    when (state) {
                        AccountLoadState.Loading -> {
                            _uiState.value = _uiState.value.copy(accountLoadState = state)
                        }
                        is AccountLoadState.Ready -> {
                            _uiState.value = _uiState.value.copy(accountLoadState = state)
                        }
                        is AccountLoadState.Corrupt -> {
                            pendingImportConfig = null
                            _uiState.value = _uiState.value.copy(
                                accountLoadState = state,
                                pendingImportPlan = null,
                                replaceConfirmationRequired = false,
                                importError = true
                            )
                        }
                    }
                }
        }
    }

    private fun readyAccounts() =
        (_uiState.value.accountLoadState as? AccountLoadState.Ready)?.accounts

    // ── 统计 ──

    fun loadStats() {
        statisticsJob?.cancel()
        statisticsJob = viewModelScope.launch(Dispatchers.IO) {
            val summary = runCatching { statisticsRepository.loadSummary() }.getOrNull()
                ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                _uiState.value = _uiState.value.copy(
                    rawRecordCount = summary.rawRecordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    rawRecordDistinctDates = summary.rawRecordDistinctDates.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    dailySummaryCount = summary.dailySummaryCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    usageSnapshotCount = summary.usageSnapshotCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    refreshLogCount = summary.refreshLogCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    widgetErrorCount = summary.widgetErrorCount,
                    widgetBalanceCount = summary.widgetBalanceCount,
                    alarmCounters = AlarmCounterSnapshot(
                        totalSet = summary.alarmCounters.totalSet,
                        totalFired = summary.alarmCounters.totalFired,
                        totalCancelled = summary.alarmCounters.totalCancelled,
                        totalDropped = summary.alarmCounters.totalDropped
                    ),
                    crashCount = summary.crashCount,
                    statisticsLoaded = true
                )
            }
        }
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
        val accounts = readyAccounts()
        if (accounts == null) {
            pendingImportConfig = null
            _uiState.value = _uiState.value.copy(
                pendingImportPlan = null,
                replaceConfirmationRequired = false,
                importError = true
            )
            return false
        }
        val plan = importPlanner.plan(config, accounts, ImportMode.MERGE)
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
        val accounts = readyAccounts() ?: return
        val basePlan = importPlanner.plan(config, accounts, mode)
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

    suspend fun requestApplyImport(): Boolean {
        val plan = _uiState.value.pendingImportPlan ?: return false
        if (!plan.canApply) return false
        if (plan.mode == ImportMode.REPLACE_ALL) {
            _uiState.value = _uiState.value.copy(replaceConfirmationRequired = true)
            return false
        }
        return runImportIfIdle(plan, confirmedFullReplace = false)
    }

    suspend fun confirmReplaceImport(): Boolean {
        val plan = _uiState.value.pendingImportPlan ?: return false
        if (!_uiState.value.replaceConfirmationRequired || plan.mode != ImportMode.REPLACE_ALL) {
            return false
        }
        return runImportIfIdle(plan, confirmedFullReplace = true)
    }

    private suspend fun runImportIfIdle(
        plan: BackupImportPlan,
        confirmedFullReplace: Boolean
    ): Boolean {
        if (!importInProgressGuard.compareAndSet(false, true)) return false
        _uiState.value = _uiState.value.copy(importInProgress = true)
        return try {
            applyPendingImport(plan, confirmedFullReplace)
        } finally {
            importInProgressGuard.set(false)
            _uiState.value = _uiState.value.copy(importInProgress = false)
        }
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

    private suspend fun applyPendingImport(
        plan: BackupImportPlan,
        confirmedFullReplace: Boolean
    ): Boolean = try {
        withContext(Dispatchers.IO) {
            if (importPlanner.usesAtomicSettingsPublication) {
                importPlanner.applyAsync(plan, confirmedFullReplace)
            } else {
                // Legacy storage has no Room transaction boundary. Preserve its existing
                // synchronous UI contract while production imports use applyAsync above.
                importPlanner.apply(plan, confirmedFullReplace)
            }
        }
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

    suspend fun exportConfiguration(uri: Uri, includeTokens: Boolean): Boolean {
        val accounts = exportableAccounts(includeTokens) ?: return false
        return withContext(Dispatchers.IO) {
            val settings = SettingsRepositoryProvider.get(getApplication()).readSnapshot()
            ConfigManager.exportToUri(
                getApplication(),
                uri,
                accounts,
                settings,
                includeTokens
            )
        }
    }

    /**
     * Configuration backup must remain available while account reconciliation
     * is broken. Prefer the validated UI payload, then non-secret Room metadata
     * for a credential-free export. Corrupt state never trusts legacy credentials: Room supplies the
     * canonical account IDs and the only provider settings retained here are
     * the non-secret values already published in provider_config_json.
     */
    private suspend fun exportableAccounts(includeTokens: Boolean): List<AccountInfo>? {
        readyAccounts()?.let { return it }
        if (includeTokens) return null
        val rows = database.accountDao().getAllForMigration()
            .filter { it.state == AccountState.VERIFIED }
        return runCatching { rows.map(AccountMapper::toCredentialFreeAccount) }.getOrNull()
    }

    fun startHistoryExport(uri: Uri): String? = startHistoryOperation(
        DataOperationKind.EXPORT_HISTORY,
        uri
    )

    fun startHistoryImport(uri: Uri): String? = startHistoryOperation(
        DataOperationKind.IMPORT_HISTORY,
        uri
    )

    fun cancelHistoryOperation() {
        val operationId = historyOperationOwner ?: return
        val state = _uiState.value.dataOperationState
        if (state is DataOperationState.Running && state.operationId == operationId) {
            _uiState.value = _uiState.value.copy(
                dataOperationState = DataOperationState.Cancelled(operationId, state.kind)
            )
        }
        historyOperationJob?.cancel()
    }

    fun acknowledgeHistoryOperation(operationId: String) {
        val state = _uiState.value.dataOperationState
        val terminalId = when (state) {
            is DataOperationState.Succeeded -> state.operationId
            is DataOperationState.Failed -> state.operationId
            is DataOperationState.Cancelled -> state.operationId
            else -> null
        }
        if (terminalId == operationId) {
            _uiState.value = _uiState.value.copy(dataOperationState = DataOperationState.Idle)
        }
    }

    /** Compatibility bridge for non-StateFlow callers. */
    suspend fun exportHistory(uri: Uri): Boolean {
        val operationId = startHistoryExport(uri) ?: return false
        val job = historyOperationJob ?: return false
        job.join()
        return (_uiState.value.dataOperationState as? DataOperationState.Succeeded)
            ?.operationId == operationId
    }

    /** Compatibility bridge for non-StateFlow callers. */
    suspend fun importHistory(uri: Uri): DataExporter.ImportResult? {
        val operationId = startHistoryImport(uri) ?: return null
        val job = historyOperationJob ?: return null
        job.join()
        val state = _uiState.value.dataOperationState as? DataOperationState.Succeeded
        val summary = state?.takeIf { it.operationId == operationId }?.importSummary ?: return null
        return DataExporter.ImportResult(
            summary.summariesInFile,
            summary.summariesImported,
            summary.recordsInFile,
            summary.recordsImported,
            summary.snapshotsInFile,
            summary.snapshotsImported,
            summary.logsInFile,
            summary.logsImported
        )
    }

    private fun startHistoryOperation(kind: DataOperationKind, uri: Uri): String? {
        historyOperationJob?.cancel()
        val operationId = UUID.randomUUID().toString()
        historyOperationOwner = operationId
        _uiState.value = _uiState.value.copy(
            dataOperationState = DataOperationState.Running(operationId, kind, 0)
        )
        historyOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var exportSucceeded = false
                val importResult = when (kind) {
                    DataOperationKind.EXPORT_HISTORY -> {
                        exportSucceeded = historyRepository.export(uri) { progress ->
                            publishOperationProgress(operationId, kind, progress)
                        }
                        null
                    }
                    DataOperationKind.IMPORT_HISTORY -> historyRepository.import(uri) { progress ->
                        publishOperationProgress(operationId, kind, progress)
                    }
                }
                currentCoroutineContext().ensureActive()
                val succeeded = exportSucceeded || importResult != null
                val terminal = if (succeeded) {
                    DataOperationState.Succeeded(
                        operationId,
                        kind,
                        importResult?.let(DataImportSummary::from)
                    )
                } else {
                    DataOperationState.Failed(operationId, kind)
                }
                publishOperationTerminal(operationId, terminal)
                if (succeeded && kind == DataOperationKind.IMPORT_HISTORY) loadStats()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                publishOperationTerminal(
                    operationId,
                    DataOperationState.Cancelled(operationId, kind)
                )
            } catch (_: Exception) {
                publishOperationTerminal(
                    operationId,
                    DataOperationState.Failed(operationId, kind)
                )
            }
        }
        return operationId
    }

    private fun publishOperationProgress(
        operationId: String,
        kind: DataOperationKind,
        progress: Int
    ) {
        _uiState.update { state ->
            val current = state.dataOperationState
            if (historyOperationOwner == operationId &&
                current is DataOperationState.Running && current.operationId == operationId
            ) {
                state.copy(
                    dataOperationState = DataOperationState.Running(
                        operationId,
                        kind,
                        progress.coerceIn(0, 100)
                    )
                )
            } else {
                state
            }
        }
    }

    private fun publishOperationTerminal(
        operationId: String,
        terminal: DataOperationState
    ) {
        _uiState.update { state ->
            if (historyOperationOwner == operationId) {
                state.copy(dataOperationState = terminal)
            } else {
                state
            }
        }
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
            val outcome = runCatching {
                when (action) {
                PendingAction.ClearRawRecords -> {
                    database.historyDao().clearRecords()
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearDailySummaries -> {
                    database.historyDao().clearSummaries()
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearUsageSnapshots -> {
                    database.usageDao().clearSnapshots()
                    res.getString(R.string.data_cleared_toast)
                }
                PendingAction.ClearRefreshLogs -> {
                    database.eventLogDao().clearAll()
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
                    SettingsRepositoryProvider.get(ctx).publishSnapshot(
                        SettingsSnapshot(AppSettingsEntity(updatedAt = System.currentTimeMillis())),
                        System.currentTimeMillis()
                    )
                    res.getString(R.string.data_reset_toast)
                }
                PendingAction.ResetEntireApp -> {
                    appResetCoordinator.reset()
                    res.getString(R.string.data_reset_app_toast)
                }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (outcome.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        pendingAction = null,
                        resultMessage = outcome.getOrThrow()
                    )
                    loadStats()
                } else {
                    _uiState.value = _uiState.value.copy(
                        pendingAction = null,
                        resultMessage = res.getString(R.string.data_operation_failed)
                    )
                }
            }
        }
    }
}
