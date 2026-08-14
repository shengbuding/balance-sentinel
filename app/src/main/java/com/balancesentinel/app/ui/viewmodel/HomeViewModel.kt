package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.balancesentinel.app.data.util.Logger
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.RefreshRuntime
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountSaveResult
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.AccountLifecycleManager
import com.balancesentinel.app.data.repository.AccountMutationCoordinator
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.BalanceRepository
import com.balancesentinel.app.data.repository.ConfigManager
import com.balancesentinel.app.data.repository.CleanupScheduler
import com.balancesentinel.app.work.MidnightMaintenanceDependencies
import com.balancesentinel.app.work.MidnightWorkSchedulingGate
import com.balancesentinel.app.work.MidnightWorkScheduler
import com.balancesentinel.app.data.repository.appendRoomEvent
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.RefreshStats
import com.balancesentinel.app.data.repository.RefreshStatsStore
import com.balancesentinel.app.data.repository.RoomSettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.data.repository.SnoozeInfo
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.widget.AccountBalanceFailure
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.BalanceCacheSnapshot
import com.balancesentinel.app.widget.StaticWidgetProvider_2x1
import com.balancesentinel.app.widget.StaticWidgetProvider_2x2
import com.balancesentinel.app.widget.StaticWidgetProvider_3x1
import com.balancesentinel.app.widget.StaticWidgetProvider_4x2
import com.balancesentinel.app.widget.StaticWidgetProvider_5x1
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

data class HomeUiState(
    val accountLoadState: AccountLoadState = AccountLoadState.Loading,
    val accounts: List<AccountInfo> = emptyList(),
    val accountBalances: Map<String, BalanceResponse?> = emptyMap(),
    val refreshStates: Map<String, AccountRefreshUiState> = emptyMap(),
    val isLoading: Boolean = false,
    /** Error produced by a refresh operation, also represented on each account state. */
    val refreshErrorMessage: String? = null,
    /** Error produced by account loading or account mutations. */
    val operationErrorMessage: String? = null,
    /** Backward-compatible effective error for existing callers. */
    val errorMessage: String? = null,
    val lastRefreshTime: Long = 0L,
    val crashLogs: List<CrashLogger.CrashEntry> = emptyList(),
    val statusSummary: com.balancesentinel.app.data.repository.StatusSummary? = null,
    val refreshIntervalSeconds: Int = RoomSettingsRepository.DEFAULT_FOREGROUND_INTERVAL_SECONDS,
    val alertEnabled: Boolean = false,
    val alertThreshold: Float = 0f,
    val changeAlertEnabled: Boolean = false,
    val changeAlertThreshold: Float = 0f,
    val changeAlertPeriodMinutes: Int = 0,
    val snoozeInfo: com.balancesentinel.app.data.repository.SnoozeInfo = com.balancesentinel.app.data.repository.SnoozeInfo(),
    val snoozeDurationMinutes: Int = 60,
    val backgroundRefreshIntervalSeconds: Int? = 900,
    val settingsLoading: Boolean = true,
    val showTotalBalanceInNotification: Boolean = true,
    val accountAlertSettings: List<com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity> = emptyList(),
    val notificationSelections: List<com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity> = emptyList()
)

class HomeViewModel @JvmOverloads constructor(
    application: Application,
    // test-only: inject mocks for unit testing
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(application),
    private val repository: BalanceRepository = BalanceRepository(),
    // Tests provide a deterministic gateway; production resolves the Application singleton.
    private val gateway: com.balancesentinel.app.data.refresh.RefreshGateway? = null,
    private val injectedAccountUiRepository: AccountUiRepository? = null,
    private val injectedAccountMutationCoordinator: AccountMutationCoordinator? = null,
    @Suppress("UNUSED_PARAMETER") cleanupAction: suspend (Context) -> Unit = { context ->
        CleanupScheduler.runCleanup(context)
    }
) : AndroidViewModel(application) {

    private val settingsRepository: SettingsRepository = SettingsRepositoryProvider.get(application)
    private val accountSource: AccountUiRepository = injectedAccountUiRepository
        ?: RoomAccountUiRepository(
            RoomAccountRepository(WalletDatabaseProvider.get(application)),
            EncryptedPreferencesCredentialStore(application)
        )
    private val mutationCoordinator: AccountMutationCoordinator = injectedAccountMutationCoordinator
        ?: AccountLifecycleManager(application).mutationCoordinator()
    private var accountCollectionJob: Job? = null
    private var latestBalanceSnapshot = BalanceCacheSnapshot(emptyList(), emptyList())
    private val refreshCacheBaselines = mutableMapOf<String, RefreshCacheBaseline>()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _refreshStats = MutableStateFlow<RefreshStats?>(null)
    val refreshStats: StateFlow<RefreshStats?> = _refreshStats.asStateFlow()

    private val _events = Channel<HomeUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    val uiEvents = events
    private val refreshRequestCounter = AtomicLong(0L)
    private var nonRefreshErrorMessage: String? = null

    fun loadRefreshStats() {
        viewModelScope.launch {
            try {
                _refreshStats.value = RefreshStatsStore.getStats(getApplication())
            } catch (_: Exception) {
                _refreshStats.value = null
            }
        }
    }

    init {
        observeBalanceCache()
        observeAccounts()
        observeSettings()
        loadCrashLogs()
        checkMissedRefreshes()
        loadStatusSummary()
        scheduleMidnightAndCheckSummary()
    }

    private fun observeBalanceCache() {
        viewModelScope.launch {
            BalanceWidgetDataStore.observeSnapshot(getApplication())
                .catch { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w("HomeViewModel", "balance cache observation failed: ${error.message}")
                }
                .collect { snapshot ->
                    latestBalanceSnapshot = snapshot
                    if (readyAccounts() != null) {
                        projectBalanceCacheSnapshot(snapshot)
                    }
                }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.snapshot.collect { state ->
                when (state) {
                    SettingsSnapshotState.Loading ->
                        _uiState.value = _uiState.value.copy(settingsLoading = true)
                    is SettingsSnapshotState.Ready -> applySettingsSnapshot(state.value)
                }
            }
        }
    }

    private fun applySettingsSnapshot(snapshot: SettingsSnapshot) {
        val app = snapshot.appSettings
        val now = System.currentTimeMillis()
        val activeSnoozes = snapshot.snoozes.filter { it.snoozedUntil > now }
        val sharedInterval = snapshot.sharedRefreshIntervalSeconds
        _uiState.value = _uiState.value.copy(
            refreshIntervalSeconds = sharedInterval,
            backgroundRefreshIntervalSeconds = snapshot.effectiveBackgroundCadenceSeconds,
            alertEnabled = app.alertEnabled,
            alertThreshold = app.alertThreshold.toFloat(),
            changeAlertEnabled = app.changeAlertEnabled,
            changeAlertThreshold = app.changeAlertThreshold.toFloat(),
            changeAlertPeriodMinutes = app.changeAlertPeriodMinutes,
            snoozeInfo = SnoozeInfo(
                anySnoozed = activeSnoozes.isNotEmpty(),
                maxRemainingMs = activeSnoozes.maxOfOrNull { it.snoozedUntil - now } ?: 0L,
                snoozedAccountIds = activeSnoozes.map { it.accountId }
            ),
            snoozeDurationMinutes = app.snoozeDurationMinutes,
            settingsLoading = false,
            showTotalBalanceInNotification = app.showTotalBalanceInNotification,
            accountAlertSettings = snapshot.accountAlertSettings,
            notificationSelections = snapshot.notificationSelections
        )
    }

    private fun observeAccounts() {
        accountCollectionJob?.cancel()
        accountCollectionJob = viewModelScope.launch {
            accountSource.observe()
                .catch { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    emit(AccountLoadState.Corrupt(asCorruption(error)))
                }
                .collect { state ->
                    when (state) {
                        AccountLoadState.Loading -> {
                            _uiState.value = _uiState.value.copy(accountLoadState = state)
                        }
                        is AccountLoadState.Ready -> {
                            if (nonRefreshErrorMessage == accountCorruptionMessage()) {
                                nonRefreshErrorMessage = null
                            }
                            _uiState.value = _uiState.value.copy(
                                accountLoadState = state,
                                accounts = state.accounts,
                                operationErrorMessage = nonRefreshErrorMessage,
                                errorMessage = nonRefreshErrorMessage
                            )
                            syncRefreshStates(state.accounts)
                            loadCachedBalancesForReadyAccounts()
                        }
                        is AccountLoadState.Corrupt -> {
                            nonRefreshErrorMessage = accountCorruptionMessage()
                            refreshCacheBaselines.clear()
                            _uiState.value = _uiState.value.copy(
                                accountLoadState = state,
                                accounts = emptyList(),
                                accountBalances = emptyMap(),
                                refreshStates = emptyMap(),
                                isLoading = false,
                                lastRefreshTime = 0L,
                                refreshErrorMessage = null,
                                operationErrorMessage = accountCorruptionMessage(),
                                errorMessage = accountCorruptionMessage()
                            )
                        }
                    }
                }
        }
    }

    private fun resubscribeAccounts() {
        observeAccounts()
    }

    private fun readyAccounts(): List<AccountInfo>? =
        (_uiState.value.accountLoadState as? AccountLoadState.Ready)?.accounts

    private fun readyAccount(accountId: String): AccountInfo? =
        readyAccounts()?.firstOrNull { it.id == accountId }

    private fun syncRefreshStates(accounts: List<AccountInfo>) {
        val ids = accounts.map { it.id }.toSet()
        refreshCacheBaselines.keys.retainAll(ids)
        val retained = _uiState.value.refreshStates
            .filterKeys { it in ids }
            .toMutableMap()
        accounts.forEach { account ->
            retained.putIfAbsent(account.id, AccountRefreshUiState())
        }
        publishRefreshStates(retained)
    }

    private fun publishRefreshStates(states: Map<String, AccountRefreshUiState>) {
        val current = _uiState.value
        val refreshError = states.values.firstOrNull { it.errorMessage != null }?.errorMessage
        _uiState.value = current.copy(
            refreshStates = states,
            isLoading = states.values.any { it.isLoading },
            lastRefreshTime = states.values.mapNotNull { it.lastSuccessAt }.maxOrNull() ?: 0L,
            refreshErrorMessage = refreshError,
            errorMessage = nonRefreshErrorMessage ?: refreshError
        )
    }

    private fun setNonRefreshError(message: String?) {
        nonRefreshErrorMessage = message
        val refreshError = _uiState.value.refreshStates.values
            .firstOrNull { it.errorMessage != null }
            ?.errorMessage
        _uiState.value = _uiState.value.copy(
            operationErrorMessage = message,
            refreshErrorMessage = refreshError,
            errorMessage = message ?: refreshError
        )
    }

    private fun clearNonRefreshError() = setNonRefreshError(null)

    private fun beginRefresh(
        accountId: String,
        cacheSnapshot: BalanceCacheSnapshot = BalanceWidgetDataStore.getSnapshot(getApplication())
    ): Long {
        val requestId = refreshRequestCounter.incrementAndGet()
        refreshCacheBaselines[accountId] = RefreshCacheBaseline(
            requestId = requestId,
            accountSnapshot = cacheSnapshot.forAccount(accountId)
        )
        val previous = _uiState.value.refreshStates[accountId] ?: AccountRefreshUiState()
        val states = _uiState.value.refreshStates.toMutableMap()
        states[accountId] = previous.copy(
            requestId = requestId,
            isLoading = true,
            errorMessage = null
        )
        publishRefreshStates(states)
        return requestId
    }

    private fun clearRefreshCacheBaseline(accountId: String, requestId: Long) {
        if (refreshCacheBaselines[accountId]?.requestId == requestId) {
            refreshCacheBaselines.remove(accountId)
        }
    }

    private fun isCurrentRefresh(accountId: String, requestId: Long): Boolean =
        _uiState.value.refreshStates[accountId]?.requestId == requestId

    private fun updateRefreshState(
        accountId: String,
        requestId: Long,
        transform: (AccountRefreshUiState) -> AccountRefreshUiState
    ): Boolean {
        val current = _uiState.value.refreshStates[accountId] ?: return false
        if (current.requestId != requestId) return false
        val states = _uiState.value.refreshStates.toMutableMap()
        states[accountId] = transform(current)
        publishRefreshStates(states)
        return true
    }

    private suspend fun emitError(accountId: String?, message: String) {
        _events.send(HomeUiEvent.ShowError(accountId, message))
    }

    private suspend fun markRefreshFailure(
        account: AccountInfo,
        requestId: Long,
        message: String,
        dataTimestamp: Long? = null,
        emitSnackbar: Boolean = true,
        cause: Throwable? = null
    ) {
        if (isCurrentRefresh(account.id, requestId)) {
            val updated = updateRefreshState(account.id, requestId) { state ->
                state.copy(
                    isLoading = false,
                    dataTimestamp = dataTimestamp ?: state.dataTimestamp,
                    stale = true,
                    errorMessage = message
                )
            }
            if (updated && emitSnackbar) emitError(account.id, message)
        }
        cause?.let {
            Logger.e("HomeViewModel", "refresh failed for ${account.label}", it)
        }
    }

    private fun toBalanceResponse(balance: com.balancesentinel.app.data.api.UnifiedBalance): BalanceResponse =
        BalanceResponse(
            isAvailable = balance.isAvailable,
            balanceInfos = balance.balances.map { entry ->
                BalanceInfo(
                    currency = entry.currency,
                    totalBalance = entry.totalBalance.toString(),
                    grantedBalance = entry.grantedBalance.toString(),
                    toppedUpBalance = entry.toppedUpBalance.toString()
                )
            }
        )

    private suspend fun applyRefreshResult(
        account: AccountInfo,
        requestId: Long,
        result: com.balancesentinel.app.data.refresh.AccountRefreshResult
    ) {
        if (!isCurrentRefresh(account.id, requestId)) return
        clearRefreshCacheBaseline(account.id, requestId)
        when (result) {
            is com.balancesentinel.app.data.refresh.AccountRefreshResult.Committed -> {
                val balances = _uiState.value.accountBalances.toMutableMap()
                balances[account.id] = toBalanceResponse(result.balance)
                _uiState.value = _uiState.value.copy(accountBalances = balances)
                updateRefreshState(account.id, requestId) { state ->
                    state.copy(
                        isLoading = false,
                        lastSuccessAt = System.currentTimeMillis(),
                        dataTimestamp = result.dataTimestamp ?: System.currentTimeMillis(),
                        stale = false,
                        errorMessage = null
                    )
                }
            }
            is com.balancesentinel.app.data.refresh.AccountRefreshResult.Failed -> {
                val message = "[${account.label}] ${result.failure.message}"
                markRefreshFailure(
                    account = account,
                    requestId = requestId,
                    message = message,
                    dataTimestamp = result.dataTimestamp
                )
            }
            is com.balancesentinel.app.data.refresh.AccountRefreshResult.Stale -> {
                markRefreshFailure(
                    account = account,
                    requestId = requestId,
                    message = result.failure.message,
                    emitSnackbar = false
                )
            }
            is com.balancesentinel.app.data.refresh.AccountRefreshResult.Skipped -> {
                val message = "[${account.label}] ${result.reason}"
                markRefreshFailure(account, requestId, message)
            }
        }
    }

    private fun asCorruption(error: Throwable): DataCorruptionException =
        error as? DataCorruptionException
            ?: DataCorruptionException("Account UI state cannot be read", error)

    private fun accountCorruptionMessage(): String =
        getApplication<Application>().getString(R.string.account_data_corrupt)

    /** Re-enter the account stream after external imports or page recreation. */
    fun loadCachedBalances() {
        resubscribeAccounts()
    }

    /** 从 Widget 缓存恢复首页余额数据，避免显示误导的"查询失败" */
    private fun loadCachedBalancesForReadyAccounts() {
        try {
            projectBalanceCacheSnapshot(latestBalanceSnapshot)
            if (gateway == null && getApplication<Application>() !is com.balancesentinel.app.DeepSeekApp) {
                Logger.w("HomeViewModel", "loadCachedBalances: no cached data found")
            }
        } catch (e: Exception) { Logger.e("HomeViewModel", "loadCachedBalances failed", e) }
    }

    /** Project durable cache changes without generating user-facing refresh events. */
    private fun projectBalanceCacheSnapshot(snapshot: BalanceCacheSnapshot) {
        val accounts = readyAccounts() ?: return
        if (accounts.isEmpty()) {
            Logger.i("HomeViewModel", "loadCachedBalances: no accounts")
            return
        }

        val byAccount = snapshot.balances.groupBy { it.accountId }
        val failures = snapshot.failures.associateBy { it.accountId }
        val accountIds = accounts.mapTo(mutableSetOf()) { it.id }
        val accountBalances = _uiState.value.accountBalances
            .filterKeys { it in accountIds }
            .toMutableMap()
        val states = _uiState.value.refreshStates.toMutableMap()

        accounts.forEach { account ->
            val entries = byAccount[account.id].orEmpty()
            val failure = failures[account.id]
            val state = states[account.id] ?: AccountRefreshUiState()
            val accountSnapshot = AccountCacheSnapshot(entries, failure)
            if (state.isLoading) {
                val baseline = refreshCacheBaselines[account.id]
                if (baseline == null || baseline.accountSnapshot == accountSnapshot) {
                    return@forEach
                }
                refreshCacheBaselines.remove(account.id)
            }
            if (entries.isNotEmpty()) {
                accountBalances[account.id] = BalanceResponse(
                    isAvailable = entries.all { it.isAvailable },
                    balanceInfos = entries.map { entry ->
                        BalanceInfo(
                            currency = entry.currency,
                            totalBalance = entry.totalBalance,
                            grantedBalance = entry.grantedBalance,
                            toppedUpBalance = entry.toppedUpBalance
                        )
                    }
                )
                val timestamp = entries.maxOfOrNull { it.lastUpdated } ?: state.dataTimestamp
                val staleReason = failure?.reason ?: entries.firstOrNull { it.stale }?.staleReason
                states[account.id] = state.copy(
                    isLoading = false,
                    lastSuccessAt = if (staleReason == null) {
                        maxOf(state.lastSuccessAt ?: 0L, timestamp ?: 0L).takeIf { it > 0L }
                    } else state.lastSuccessAt,
                    dataTimestamp = timestamp,
                    stale = staleReason != null,
                    errorMessage = staleReason?.let { "[${account.label}] $it" }
                )
            } else if (failure != null) {
                accountBalances.remove(account.id)
                states[account.id] = state.copy(
                    isLoading = false,
                    stale = false,
                    dataTimestamp = null,
                    errorMessage = "[${account.label}] ${failure.reason}"
                )
            } else {
                accountBalances.remove(account.id)
            }
        }

        _uiState.value = _uiState.value.copy(accountBalances = accountBalances)
        publishRefreshStates(states)
    }

    // ── 午夜调度 ──

    private fun scheduleMidnightAndCheckSummary() {
        try {
            MidnightWorkSchedulingGate.withLock {
                MidnightWorkScheduler().reconcile(
                    getApplication(),
                    zoneId = MidnightMaintenanceDependencies.zoneIdProvider()
                )
            }
        } catch (e: Exception) { Logger.w("HomeViewModel", "midnight_reconcile_failed", e) }
    }

    // ── 崩溃日志 ──

    fun loadCrashLogs() {
        try {
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(crashLogs = CrashLogger.getCrashes(app))
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
    }

    fun clearCrashes() {
        try {
            CrashLogger.clear(getApplication())
            _uiState.value = _uiState.value.copy(crashLogs = emptyList())
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
    }

    // ── 账户管理 ──

    fun addAccount(
        label: String,
        apiKey: String,
        providerType: ProviderType = ProviderType.DEEPSEEK,
        extraSettings: Map<String, String> = emptyMap()
    ) {
        if (label.isBlank() || apiKey.isBlank()) return
        val fieldValues = extraSettings + ("apiKey" to apiKey)
        if (!ProviderConfigs.validateFieldValues(providerType, fieldValues)) return
        val providerFields = ProviderConfigs.getConfigFields(providerType)
        val extraCredentials = providerFields
            .filter { it.storage == ConfigFieldStorage.EXTRA_CREDENTIAL }
            .mapNotNull { field -> extraSettings[field.key]?.let { field.key to it } }
            .toMap()
        val settings = providerFields
            .filter { it.storage == ConfigFieldStorage.SETTING }
            .mapNotNull { field -> extraSettings[field.key]?.let { field.key to it } }
            .toMap()
        addAccount(
            AccountDraft(
                label = label,
                apiKey = apiKey,
                providerType = providerType,
                extraCredentials = extraCredentials,
                extraSettings = settings,
                usageScript = null,
                usageScriptEnabled = true,
                authorizedScriptOrigins = emptySet()
            )
        )
    }

    fun addAccount(draft: AccountDraft) {
        if (draft.label.isBlank() || draft.apiKey.isBlank()) return
        val values = draft.extraCredentials + draft.extraSettings + ("apiKey" to draft.apiKey)
        if (!ProviderConfigs.validateApiKey(draft.providerType, draft.apiKey) ||
            !ProviderConfigs.validateFieldValues(draft.providerType, values)
        ) return

        if (readyAccounts() == null) return
        viewModelScope.launch {
            try {
                when (val result = mutationCoordinator.save(existingId = null, draft = draft)) {
                    is com.balancesentinel.app.data.repository.AccountMutationResult.Saved -> {
                        if (result.result is AccountSaveResult.Conflict) {
                            setNonRefreshError(getApplication<Application>().getString(R.string.account_key_conflict))
                        } else {
                            clearNonRefreshError()
                            resubscribeAccounts()
                            refreshBalance()
                        }
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                setNonRefreshError(error.message ?: "账户保存失败")
            }
        }
    }

    fun removeAccount(id: String) {
        if (readyAccounts()?.any { it.id == id } != true) return
        viewModelScope.launch {
            try {
                mutationCoordinator.delete(id)
                clearNonRefreshError()
                _uiState.value = _uiState.value.copy(
                    accountBalances = _uiState.value.accountBalances - id
                )
                resubscribeAccounts()
            } catch (error: Exception) {
                setNonRefreshError(error.message ?: "账户删除失败")
            }
        }
    }

    /**
     * 删除账户及其关联数据
     * 关联数据包括：Widget缓存、预警状态、原始记录、日摘要、用量快照
     */
    fun removeAccountWithData(id: String) {
        removeAccount(id)
    }

    fun renameAccount(id: String, newLabel: String) {
        if (newLabel.isBlank()) return
        val current = readyAccounts()?.firstOrNull { it.id == id } ?: return
        editAccount(id, current.toDraft(label = newLabel))
    }

    /**
     * 编辑账户（更新标签、API Key、额外设置和自定义脚本）
     * 如果API Key变化，需要重新计算ID并迁移关联数据
     */
    fun editAccount(
        id: String,
        draft: AccountDraft
    ) {
        if (draft.label.isBlank() || draft.apiKey.isBlank()) return
        val values = draft.extraCredentials + draft.extraSettings + ("apiKey" to draft.apiKey)
        if (!ProviderConfigs.validateApiKey(draft.providerType, draft.apiKey) ||
            !ProviderConfigs.validateFieldValues(draft.providerType, values)
        ) return

        if (readyAccounts()?.any { it.id == id } != true) return
        viewModelScope.launch {
            try {
                when (val result = mutationCoordinator.save(existingId = id, draft = draft)) {
                    is com.balancesentinel.app.data.repository.AccountMutationResult.Saved -> {
                        if (result.result is AccountSaveResult.Conflict) {
                            setNonRefreshError(getApplication<Application>().getString(R.string.account_key_conflict))
                        } else {
                            clearNonRefreshError()
                            resubscribeAccounts()
                            refreshBalance()
                        }
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                setNonRefreshError(error.message ?: "账户保存失败")
            }
        }
    }

    fun editAccount(
        id: String,
        newLabel: String,
        newApiKey: String,
        extraSettings: Map<String, String> = emptyMap(),
        usageScript: String? = null
    ) {
        if (newLabel.isBlank() || newApiKey.isBlank()) return

        val currentAccount = readyAccounts()?.firstOrNull { it.id == id } ?: return
        editAccount(
            id,
            AccountDraft(
                label = newLabel,
                apiKey = newApiKey,
                providerType = currentAccount.providerType,
                extraCredentials = currentAccount.extraCredentials,
                extraSettings = extraSettings,
                usageScript = usageScript,
                usageScriptEnabled = currentAccount.usageScriptEnabled,
                authorizedScriptOrigins = currentAccount.authorizedScriptOrigins
            )
        )
    }

    private fun AccountInfo.toDraft(
        label: String = this.label,
        apiKey: String = this.apiKey,
        extraSettings: Map<String, String> = this.extraSettings,
        usageScript: String? = this.usageScript
    ) = AccountDraft(
        label = label,
        apiKey = apiKey,
        providerType = providerType,
        extraCredentials = extraCredentials,
        extraSettings = extraSettings,
        usageScript = usageScript,
        usageScriptEnabled = usageScriptEnabled,
        authorizedScriptOrigins = authorizedScriptOrigins
    )

    // ── 全局设置 ──

    fun setRefreshInterval(seconds: Int) {
        val sharedInterval = seconds.coerceAtLeast(1)
        _uiState.value = _uiState.value.copy(refreshIntervalSeconds = sharedInterval)
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                current.copy(
                    appSettings = current.appSettings.copy(
                        foregroundMonitoringIntervalSeconds = sharedInterval,
                        // Preserve an explicit background-disabled state. The
                        // shared cadence is still used by the foreground service.
                        backgroundRefreshIntervalSeconds =
                            current.appSettings.backgroundRefreshIntervalSeconds?.let { sharedInterval }
                    )
                )
            }
            // DeepSeekApp owns background WorkManager reconciliation. Notify
            // the foreground service only after Room publishes the new value.
            notifyServiceRescheduleIfDesired()
        }
        if (!readyAccounts().isNullOrEmpty()) refreshBalance()
    }

    /** Re-schedules a live user-owned session without changing its intent. */
    private suspend fun notifyServiceRescheduleIfDesired() {
        val context = getApplication<Application>()
        val desired = runCatching {
            WalletDatabaseProvider.get(context).monitoringStateDao().get()?.desired == true
        }.getOrDefault(false)
        if (!desired) return
        runCatching {
            ForegroundServiceStarter(userInitiated = false).start(context)
        }.onFailure { error ->
            Logger.w("HomeViewModel", "service_reschedule_failed", error)
        }
    }

    fun setAlertEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(alertEnabled = enabled)
        updateAppSettings { it.copy(alertEnabled = enabled) }
    }

    fun setAlertThreshold(threshold: Float) {
        // 更新阈值后自动解除所有暂停，确保新设置立即生效
        _uiState.value = _uiState.value.copy(
            alertThreshold = threshold,
            snoozeInfo = SnoozeInfo()
        )
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                current.copy(
                    appSettings = current.appSettings.copy(alertThreshold = threshold.toDouble()),
                    snoozes = emptyList()
                )
            }
        }
    }

    fun setChangeAlertEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(changeAlertEnabled = enabled)
        updateAppSettings { it.copy(changeAlertEnabled = enabled) }
    }

    fun setChangeAlertThreshold(threshold: Float) {
        // 更新阈值后自动解除所有暂停
        _uiState.value = _uiState.value.copy(
            changeAlertThreshold = threshold,
            snoozeInfo = SnoozeInfo()
        )
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                current.copy(
                    appSettings = current.appSettings.copy(changeAlertThreshold = threshold.toDouble()),
                    snoozes = emptyList()
                )
            }
        }
    }

    fun setChangeAlertPeriodMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(changeAlertPeriodMinutes = minutes)
        updateAppSettings { it.copy(changeAlertPeriodMinutes = minutes) }
    }

    fun setSnoozeDurationMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(snoozeDurationMinutes = minutes)
        updateAppSettings { it.copy(snoozeDurationMinutes = minutes) }
    }

    fun clearAllSnooze() {
        _uiState.value = _uiState.value.copy(snoozeInfo = SnoozeInfo())
        viewModelScope.launch {
            settingsRepository.updateSnapshot { it.copy(snoozes = emptyList()) }
        }
    }

    fun refreshSnoozeInfo() {
        val ready = settingsRepository.snapshot.value as? SettingsSnapshotState.Ready ?: return
        applySettingsSnapshot(ready.value)
    }

    // ── 配置导入/导出 ──

    /** 获取配置 JSON 字符串（供导出使用） */
    fun getConfigJson(): String {
        val ready = settingsRepository.snapshot.value as? SettingsSnapshotState.Ready ?: return ""
        return ConfigManager.buildConfig(getApplication(), apiKeyManager.getAccounts(), ready.value)
    }

    private fun updateAppSettings(
        transform: (com.balancesentinel.app.data.local.settings.AppSettingsEntity) ->
            com.balancesentinel.app.data.local.settings.AppSettingsEntity
    ) {
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                current.copy(appSettings = transform(current.appSettings))
            }
        }
    }

    fun setShowTotalBalanceInNotification(enabled: Boolean) {
        updateAppSettings { it.copy(showTotalBalanceInNotification = enabled) }
    }

    fun setAccountAlertEnabled(
        accountId: String,
        currency: String,
        balanceEnabled: Boolean? = null,
        changeEnabled: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                val existing = current.accountAlert(accountId, currency)
                val updated = com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity(
                    accountId,
                    currency,
                    balanceEnabled ?: existing?.balanceAlertEnabled ?: current.appSettings.alertEnabled,
                    changeEnabled ?: existing?.changeAlertEnabled ?: current.appSettings.changeAlertEnabled
                )
                current.copy(
                    accountAlertSettings = current.accountAlertSettings
                        .filterNot { it.accountId == accountId && it.currency == currency } + updated
                )
            }
        }
    }

    fun setNotificationWalletSelected(accountId: String, currency: String, selected: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                val retained = current.notificationSelections
                    .filterNot { it.accountId == accountId && it.currency == currency }
                val values = if (selected) {
                    retained + com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity(
                        accountId,
                        currency,
                        retained.size
                    )
                } else retained
                current.copy(notificationSelections = values)
            }
        }
    }

    fun moveNotificationWallet(accountId: String, currency: String, direction: Int) {
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                val values = current.notificationSelections.toMutableList()
                val index = values.indexOfFirst { it.accountId == accountId && it.currency == currency }
                val target = (index + direction).coerceIn(0, values.lastIndex.coerceAtLeast(0))
                if (index >= 0 && target != index) {
                    val entry = values.removeAt(index)
                    values.add(target, entry)
                }
                current.copy(notificationSelections = values.mapIndexed { order, value ->
                    value.copy(displayOrder = order)
                })
            }
        }
    }

    // ── 刷新单个账户 ──

    fun refreshSingleAccount(accountId: String) {
        val account = readyAccount(accountId) ?: return
        viewModelScope.launch {
            if (readyAccount(accountId) == null) return@launch
            clearNonRefreshError()
            val requestId = beginRefresh(accountId)
            try {
                val gw = gateway ?: (getApplication<Application>() as? com.balancesentinel.app.DeepSeekApp)?.refreshGateway
                if (gw != null) {
                    val result = gw.refreshAccount(accountId, RefreshTrigger.MANUAL_ACCOUNT)
                    if (readyAccount(accountId) != null) {
                        applyRefreshResult(account, requestId, result)
                    }
                } else {
                    Logger.w("HomeViewModel", "refreshSingleAccount: no gateway available")
                }
                if (readyAccount(accountId) != null && isCurrentRefresh(accountId, requestId)) {
                    updateAllWidgets()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (readyAccount(accountId) != null && isCurrentRefresh(accountId, requestId)) {
                    val message = "[${account.label}] ${e.message ?: "Refresh failed"}"
                    markRefreshFailure(account, requestId, message, cause = e)
                }
            } finally {
                clearRefreshCacheBaseline(accountId, requestId)
                updateRefreshState(accountId, requestId) { state ->
                    state.copy(isLoading = false)
                }
            }
        }
    }

    // ── 刷新余额（遍历所有账户） ──

    fun refreshBalance() {
        val accounts = readyAccounts() ?: return
        if (accounts.isEmpty()) {
            setNonRefreshError(getApplication<Application>().getString(R.string.no_key))
            return
        }

        viewModelScope.launch {
            if (readyAccounts() == null) return@launch
            clearNonRefreshError()
            val cacheSnapshot = BalanceWidgetDataStore.getSnapshot(getApplication())
            val requestIds = accounts.associate { account ->
                account.id to beginRefresh(account.id, cacheSnapshot)
            }
            try {
                val gw = gateway ?: (getApplication<Application>() as? com.balancesentinel.app.DeepSeekApp)?.refreshGateway
                if (gw != null) {
                    val results = gw.refreshAll(RefreshTrigger.MANUAL_ALL).results
                    if (readyAccounts() == null) return@launch
                    val byAccount = results.associateBy { it.accountId }
                    accounts.forEach { account ->
                        val result = byAccount[account.id]
                            ?: com.balancesentinel.app.data.refresh.AccountRefreshResult.Skipped(
                                account.id,
                                "No refresh result"
                            )
                        requestIds[account.id]?.let { requestId ->
                            applyRefreshResult(account, requestId, result)
                        }
                    }
                } else {
                    Logger.w("HomeViewModel", "refreshBalance: no gateway available")
                }
                if (readyAccounts() != null) updateAllWidgets()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                accounts.forEach { account ->
                    requestIds[account.id]?.let { requestId ->
                        if (isCurrentRefresh(account.id, requestId)) {
                            val message = "[${account.label}] ${e.message ?: "Refresh failed"}"
                            markRefreshFailure(account, requestId, message)
                        }
                    }
                }
                Logger.e("HomeViewModel", "refreshBalance failed", e)
            } finally {
                requestIds.forEach { (accountId, requestId) ->
                    clearRefreshCacheBaseline(accountId, requestId)
                    updateRefreshState(accountId, requestId) { state ->
                        state.copy(isLoading = false)
                    }
                }
            }
        }
    }

    private data class AccountCacheSnapshot(
        val balances: List<AccountBalance>,
        val failure: AccountBalanceFailure?
    )

    private data class RefreshCacheBaseline(
        val requestId: Long,
        val accountSnapshot: AccountCacheSnapshot
    )

    private fun BalanceCacheSnapshot.forAccount(accountId: String) = AccountCacheSnapshot(
        balances = balances.filter { it.accountId == accountId },
        failure = failures.firstOrNull { it.accountId == accountId }
    )

    private fun updateAllWidgets() {
        try {
            val context = getApplication<Application>()
            val providerClasses = listOf(
                StaticWidgetProvider_2x1::class.java,
                StaticWidgetProvider_2x2::class.java,
                StaticWidgetProvider_3x1::class.java,
                StaticWidgetProvider_4x2::class.java,
                StaticWidgetProvider_5x1::class.java
            )
            val appWidgetManager = AppWidgetManager.getInstance(context)
            for (clazz in providerClasses) {
                try {
                    val component = ComponentName(context, clazz)
                    val widgetIds = appWidgetManager.getAppWidgetIds(component)
                    if (widgetIds.isNotEmpty()) {
                        val intent = android.content.Intent(context, clazz).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                        }
                        context.sendBroadcast(intent)
                    }
                } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
            }
        } catch (e: Exception) {
            Logger.e("HomeViewModel", "updateAllWidgets failed", e)
        }
    }

    fun loadStatusSummary() {
        try {
            _uiState.value = _uiState.value.copy(
                statusSummary = RefreshScheduler.getStatusSummary(getApplication())
            )
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
    }

    private fun checkMissedRefreshes() {
        try {
            val app = getApplication<Application>()
            if (RefreshScheduler.isServiceStarting(app)) return

            if (RefreshScheduler.isServiceDead(app)) {
                val restartCount = RefreshScheduler.getRestartCount(app)
                val now = System.currentTimeMillis()
                appendRoomEvent(app, RefreshLogEntry(
                    id = now, type = RefreshLogType.SERVICE_DIED, timestamp = now,
                    message = "前台刷新服务已停止（无法自动刷新）",
                    missReason = if (restartCount > 0) {
                        "已被系统杀死 $restartCount 次。OnePlus/ColorOS 可能在后台自动冻结应用。"
                    } else {
                        "服务未运行。点击下方按钮关闭电池优化可解决"
                    }
                ))
            }

            val missed = RefreshScheduler.checkMissedRefresh(app)
            if (missed.isNotEmpty()) {
                missed.forEach { appendRoomEvent(app, it) }
            }
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
    }
}
