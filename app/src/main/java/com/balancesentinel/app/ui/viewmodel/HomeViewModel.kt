package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import androidx.core.content.ContextCompat
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
import com.balancesentinel.app.data.repository.MidnightScheduler
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
import com.balancesentinel.app.service.BalanceRefreshService
import com.balancesentinel.app.widget.BalanceWidgetDataStore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class HomeUiState(
    val accountLoadState: AccountLoadState = AccountLoadState.Loading,
    val accounts: List<AccountInfo> = emptyList(),
    val accountBalances: Map<String, BalanceResponse?> = emptyMap(),
    val isLoading: Boolean = false,
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
    private val cleanupAction: suspend (Context) -> Unit = { context ->
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

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _refreshStats = MutableStateFlow<RefreshStats?>(null)
    val refreshStats: StateFlow<RefreshStats?> = _refreshStats.asStateFlow()

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
        observeAccounts()
        observeSettings()
        loadCrashLogs()
        checkMissedRefreshes()
        loadStatusSummary()
        scheduleMidnightAndCheckSummary()
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
        _uiState.value = _uiState.value.copy(
            refreshIntervalSeconds = app.foregroundMonitoringIntervalSeconds,
            backgroundRefreshIntervalSeconds = app.backgroundRefreshIntervalSeconds,
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
                            _uiState.value = _uiState.value.copy(
                                accountLoadState = state,
                                accounts = state.accounts,
                                errorMessage = _uiState.value.errorMessage
                                    ?.takeUnless { it == accountCorruptionMessage() }
                            )
                            loadCachedBalancesForReadyAccounts()
                        }
                        is AccountLoadState.Corrupt -> {
                            _uiState.value = _uiState.value.copy(
                                accountLoadState = state,
                                accounts = emptyList(),
                                accountBalances = emptyMap(),
                                isLoading = false,
                                lastRefreshTime = 0L,
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
            _uiState.value = _uiState.value.copy(
                accountBalances = emptyMap(),
                lastRefreshTime = 0L,
                settingsLoading = _uiState.value.settingsLoading
            )
            val accounts = _uiState.value.accounts
            if (accounts.isEmpty()) {
                Logger.i("HomeViewModel", "loadCachedBalances: no accounts")
                return
            }

            val allBalances = BalanceWidgetDataStore.getAllBalances(getApplication())
            Logger.i("HomeViewModel", "loadCachedBalances: found ${allBalances.size} cached balances")

            val byAccount = allBalances.groupBy { it.accountId }

            val accountBalances = mutableMapOf<String, BalanceResponse>()
            for (account in accounts) {
                val entries = byAccount[account.id]
                if (entries == null) {
                    Logger.w("HomeViewModel", "loadCachedBalances: no cache for ${account.label}")
                    continue
                }
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
                Logger.i("HomeViewModel", "loadCachedBalances: loaded ${account.label}, isAvailable=${entries.all { it.isAvailable }}")
            }

            if (accountBalances.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    accountBalances = accountBalances,
                    lastRefreshTime = allBalances.maxOfOrNull { it.lastUpdated } ?: 0L
                )
                Logger.i("HomeViewModel", "loadCachedBalances: updated UI with ${accountBalances.size} accounts")
            }
            if (gateway == null && getApplication<Application>() !is com.balancesentinel.app.DeepSeekApp) {
                Logger.w("HomeViewModel", "loadCachedBalances: no cached data found")
            }
        } catch (e: Exception) { Logger.e("HomeViewModel", "loadCachedBalances failed", e) }
    }

    // ── 午夜调度 ──

    private fun scheduleMidnightAndCheckSummary() {
        try {
            MidnightScheduler.schedule(getApplication())
            viewModelScope.launch {
                cleanupAction(getApplication())
            }
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
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
                            _uiState.value = _uiState.value.copy(
                                errorMessage = getApplication<Application>().getString(R.string.account_key_conflict)
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(errorMessage = null)
                            resubscribeAccounts()
                            refreshBalance()
                        }
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "账户保存失败")
            }
        }
    }

    fun removeAccount(id: String) {
        if (readyAccounts()?.any { it.id == id } != true) return
        viewModelScope.launch {
            try {
                mutationCoordinator.delete(id)
                _uiState.value = _uiState.value.copy(
                    accountBalances = _uiState.value.accountBalances - id,
                    errorMessage = null
                )
                resubscribeAccounts()
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "账户删除失败")
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
                            _uiState.value = _uiState.value.copy(
                                errorMessage = getApplication<Application>().getString(R.string.account_key_conflict)
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(errorMessage = null)
                            resubscribeAccounts()
                            refreshBalance()
                        }
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "账户保存失败")
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
        _uiState.value = _uiState.value.copy(refreshIntervalSeconds = seconds)
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                current.copy(
                    appSettings = current.appSettings.copy(
                        foregroundMonitoringIntervalSeconds = seconds,
                        backgroundRefreshIntervalSeconds = if (
                            seconds >= RoomSettingsRepository.MIN_BACKGROUND_INTERVAL_SECONDS
                        ) seconds else current.backgroundRefreshIntervalSeconds
                            ?: RoomSettingsRepository.MIN_BACKGROUND_INTERVAL_SECONDS
                    )
                )
            }
        }
        // 通知前台 Service 用新间隔重新调度 Handler
        notifyServiceReschedule()
        if (!readyAccounts().isNullOrEmpty()) refreshBalance()
    }

    /** 发送 startService 意图让 Service 的 onStartCommand 触发重调度 */
    private fun notifyServiceReschedule() {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, BalanceRefreshService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
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
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {

            val gw = gateway ?: (getApplication<Application>() as? com.balancesentinel.app.DeepSeekApp)?.refreshGateway
            if (gw != null) {
                // Task 4: route through shared gateway — committer owns all persistence
                try {
                    val result = gw.refreshAccount(accountId, RefreshTrigger.MANUAL_ACCOUNT)
                    if (readyAccount(accountId) == null) return@launch
                    when (result) {
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Committed -> {
                            val balance = result.balance
                            val balanceResponse = BalanceResponse(
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
                            val currentBalances = _uiState.value.accountBalances.toMutableMap()
                            currentBalances[accountId] = balanceResponse
                            _uiState.value = _uiState.value.copy(
                                accountBalances = currentBalances,
                                lastRefreshTime = System.currentTimeMillis()
                            )
                        }
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Failed -> {
                            _uiState.value = _uiState.value.copy(
                                errorMessage = "[$account.label] ${result.failure.message}"
                            )
                        }
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Stale -> {
                            // stale — no-op, preserve cached values
                        }
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Skipped -> {
                            _uiState.value = _uiState.value.copy(
                                errorMessage = "[$account.label] ${result.reason}"
                            )
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (readyAccount(accountId) == null) return@launch
                    Logger.e("HomeViewModel", "refreshSingleAccount failed for ${account.label}", e)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "[$account.label] ${e.message ?: "查询失败"}"
                    )
                }
            } else {
                Logger.w("HomeViewModel", "refreshSingleAccount: no gateway available")
            }

            if (readyAccount(accountId) == null) return@launch
            updateAllWidgets()
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    // ── 刷新余额（遍历所有账户） ──

    fun refreshBalance() {
        val accounts = readyAccounts() ?: return
        if (accounts.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = getApplication<Application>().getString(R.string.no_key)
            )
            return
        }

        viewModelScope.launch {
            if (readyAccounts() == null) return@launch
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
            val now = System.currentTimeMillis()
            val newBalances = mutableMapOf<String, BalanceResponse?>()
            var firstError: String? = null

            val gw = gateway ?: (getApplication<Application>() as? com.balancesentinel.app.DeepSeekApp)?.refreshGateway
            if (gw != null) {
                // Task 4: route through shared gateway — committer owns all persistence
                val results = gw.refreshAll(RefreshTrigger.MANUAL_ALL).results
                if (readyAccounts() == null) return@launch
                for (result in results) {
                    val accountId = result.accountId
                    when (result) {
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Committed -> {
                            val balance = result.balance
                            newBalances[accountId] = BalanceResponse(
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
                        }
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Failed -> {
                            val label = accounts.find { it.id == accountId }?.label ?: accountId
                            if (firstError == null) firstError = "[$label] ${result.failure.message}"
                            _uiState.value.accountBalances[accountId]?.let { existing ->
                                newBalances[accountId] = existing
                            }
                        }
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Stale -> {
                            // stale — preserve cached value
                            _uiState.value.accountBalances[accountId]?.let { existing ->
                                newBalances[accountId] = existing
                            }
                        }
                        is com.balancesentinel.app.data.refresh.AccountRefreshResult.Skipped -> {
                            newBalances[accountId] = null
                        }
                    }
                }
            }
            if (gw == null) {
                Logger.w("HomeViewModel", "refreshBalance: no gateway available")
            }

            if (readyAccounts() == null) return@launch
            _uiState.value = _uiState.value.copy(
                accountBalances = newBalances,
                lastRefreshTime = now,
                errorMessage = firstError
            )

            updateAllWidgets()
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

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
