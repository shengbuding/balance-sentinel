package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import com.balancesentinel.app.data.util.Logger
import androidx.core.content.ContextCompat
import java.io.IOException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.ProviderFactory
import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderError
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.repository.AlertChecker
import com.balancesentinel.app.data.repository.AccountLifecycleManager
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.AppConfig
import com.balancesentinel.app.data.repository.BalanceRepository
import com.balancesentinel.app.data.repository.ConfigManager
import com.balancesentinel.app.data.repository.CleanupScheduler
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.MidnightScheduler
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.RefreshStats
import com.balancesentinel.app.data.repository.RefreshStatsStore
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.UsageDataStore
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
import kotlinx.coroutines.launch

data class HomeUiState(
    val accounts: List<AccountInfo> = emptyList(),
    val accountBalances: Map<String, BalanceResponse?> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val lastRefreshTime: Long = 0L,
    val crashLogs: List<CrashLogger.CrashEntry> = emptyList(),
    val statusSummary: com.balancesentinel.app.data.repository.StatusSummary? = null,
    val refreshIntervalSeconds: Int = WidgetPrefs.DEFAULT_INTERVAL,
    val alertEnabled: Boolean = false,
    val alertThreshold: Float = 0f,
    val changeAlertEnabled: Boolean = false,
    val changeAlertThreshold: Float = 0f,
    val changeAlertPeriodMinutes: Int = 0,
    val snoozeInfo: com.balancesentinel.app.data.repository.SnoozeInfo = com.balancesentinel.app.data.repository.SnoozeInfo(),
    val snoozeDurationMinutes: Int = 60
)

class HomeViewModel @JvmOverloads constructor(
    application: Application,
    // test-only: inject mocks for unit testing
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(application),
    private val repository: BalanceRepository = BalanceRepository()
) : AndroidViewModel(application) {

    private val widgetPrefs: WidgetPrefs = WidgetPrefs(application)

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
        apiKeyManager.migrateLegacyKeyIfNeeded()
        loadCrashLogs()
        loadAccounts()
        loadCachedBalances()
        checkMissedRefreshes()
        loadStatusSummary()
        _uiState.value = _uiState.value.copy(
            refreshIntervalSeconds = widgetPrefs.refreshIntervalSeconds,
            alertEnabled = widgetPrefs.alertEnabled,
            alertThreshold = widgetPrefs.alertThreshold,
            changeAlertEnabled = widgetPrefs.changeAlertEnabled,
            changeAlertThreshold = widgetPrefs.changeAlertThreshold,
            changeAlertPeriodMinutes = widgetPrefs.changeAlertPeriodMinutes,
            snoozeInfo = widgetPrefs.getSnoozeInfo(),
            snoozeDurationMinutes = widgetPrefs.snoozeDurationMinutes
        )
        scheduleMidnightAndCheckSummary()
    }

    private fun loadAccounts() {
        _uiState.value = _uiState.value.copy(accounts = apiKeyManager.getAccounts())
    }

    /** 从 Widget 缓存恢复首页余额数据，避免显示误导的"查询失败" */
    fun loadCachedBalances() {
        try {
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
            } else {
                Logger.w("HomeViewModel", "loadCachedBalances: no cached data found")
            }
        } catch (e: Exception) { Logger.e("HomeViewModel", "loadCachedBalances failed", e) }
    }

    // ── 午夜调度 ──

    private fun scheduleMidnightAndCheckSummary() {
        try {
            MidnightScheduler.schedule(getApplication())
            viewModelScope.launch {
                CleanupScheduler.runCleanup(getApplication())
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
        apiKeyManager.saveAccount(
            existingId = null,
            draft = AccountDraft(
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
        loadAccounts()
        _uiState.value = _uiState.value.copy(errorMessage = null)
        refreshBalance()
    }

    fun addAccount(draft: AccountDraft) {
        if (draft.label.isBlank() || draft.apiKey.isBlank()) return
        val values = draft.extraCredentials + draft.extraSettings + ("apiKey" to draft.apiKey)
        if (!ProviderConfigs.validateApiKey(draft.providerType, draft.apiKey) ||
            !ProviderConfigs.validateFieldValues(draft.providerType, values)
        ) return

        apiKeyManager.saveAccount(existingId = null, draft = draft)
        loadAccounts()
        _uiState.value = _uiState.value.copy(errorMessage = null)
        refreshBalance()
    }

    fun removeAccount(id: String) {
        apiKeyManager.removeAccount(id)
        BalanceWidgetDataStore.removeAccountBalance(getApplication(), id)
        widgetPrefs.removeAccountAlertState(id)
        loadAccounts()
        _uiState.value = _uiState.value.copy(
            accountBalances = _uiState.value.accountBalances - id
        )
    }

    /**
     * 删除账户及其关联数据
     * 关联数据包括：Widget缓存、预警状态、原始记录、日摘要、用量快照
     */
    fun removeAccountWithData(id: String) {
        AccountLifecycleManager(getApplication(), apiKeyManager).delete(id)
        loadAccounts()
        _uiState.value = _uiState.value.copy(
            accountBalances = _uiState.value.accountBalances - id
        )
        return

        // 1. 删除账户本身
        apiKeyManager.removeAccount(id)

        // 2. 删除Widget缓存
        BalanceWidgetDataStore.removeAccountBalance(getApplication(), id)

        // 3. 删除预警状态
        widgetPrefs.removeAccountAlertState(id)

        // 4. 删除原始记录
        RawRecordStore.removeByAccountId(getApplication(), id)

        // 5. 删除日摘要
        DailySummaryStore.removeByAccountId(getApplication(), id)

        // 6. 删除用量快照
        UsageDataStore.removeByAccountId(getApplication(), id)

        // 7. 重新加载账户列表
        loadAccounts()
        _uiState.value = _uiState.value.copy(
            accountBalances = _uiState.value.accountBalances - id
        )
    }

    fun renameAccount(id: String, newLabel: String) {
        if (newLabel.isBlank()) return
        apiKeyManager.renameAccount(id, newLabel)
        loadAccounts()
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

        val result = apiKeyManager.saveAccount(id, draft)
        if (result is com.balancesentinel.app.data.model.AccountSaveResult.Replaced) {
            migrateAccountData(result.before.id, result.account.id)
        }
        loadAccounts()
        _uiState.value = _uiState.value.copy(errorMessage = null)
        refreshBalance()
    }

    fun editAccount(
        id: String,
        newLabel: String,
        newApiKey: String,
        extraSettings: Map<String, String> = emptyMap(),
        usageScript: String? = null
    ) {
        if (newLabel.isBlank() || newApiKey.isBlank()) return

        val currentAccount = apiKeyManager.getAccount(id) ?: return
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
        return

        val oldAccount = apiKeyManager.getAccount(id) ?: return
        val result = apiKeyManager.saveAccount(
            id,
            AccountDraft(
                label = newLabel,
                apiKey = newApiKey,
                providerType = oldAccount.providerType,
                extraCredentials = oldAccount.extraCredentials,
                extraSettings = extraSettings,
                usageScript = usageScript,
                usageScriptEnabled = oldAccount.usageScriptEnabled,
                authorizedScriptOrigins = oldAccount.authorizedScriptOrigins
            )
        )
        if (result is com.balancesentinel.app.data.model.AccountSaveResult.Replaced) {
            migrateAccountData(result.before.id, result.account.id)
        }
        loadAccounts()
        _uiState.value = _uiState.value.copy(errorMessage = null)
        refreshBalance()
        return

        val newId = apiKeyManager.computeId(newApiKey)

        if (oldAccount.apiKey == newApiKey) {
            // API Key未变化，更新标签、额外设置和脚本
            apiKeyManager.renameAccount(id, newLabel)
            // 更新额外设置（如URL）
            apiKeyManager.updateExtraSettings(id, extraSettings)
            // 更新自定义脚本
            Logger.i("HomeViewModel", "Updating usage script: ${usageScript?.take(50)}")
            apiKeyManager.updateUsageScript(id, usageScript)
        } else {
            // API Key变化，需要删除旧账户并创建新账户
            // 保留关联数据（Widget、RawRecord等）
            apiKeyManager.removeAccount(id)
            val newAccount = apiKeyManager.addAccount(newLabel, newApiKey, oldAccount.providerType, extraSettings)
            // 更新新账户的脚本
            if (usageScript != null) {
                apiKeyManager.updateUsageScript(newAccount.id, usageScript)
            }

            // 如果ID不同，迁移关联数据
            if (id != newId) {
                migrateAccountData(id, newId)
            }
        }

        loadAccounts()
        _uiState.value = _uiState.value.copy(errorMessage = null)
        refreshBalance()
    }

    /**
     * 迁移账户关联数据
     * H12 修复：实现实际的数据迁移
     */
    private fun migrateAccountData(oldId: String, newId: String) {
        try {
            val context = getApplication<Application>()
            val migrationMap = mapOf(oldId to newId)
            Logger.i("HomeViewModel", "Migrating account data from $oldId to $newId")

            // 迁移原始记录
            RawRecordStore.migrateAccountIds(context, migrationMap)

            // 迁移日汇总
            DailySummaryStore.migrateAccountIds(context, migrationMap)

            // 迁移用量快照
            UsageDataStore.migrateAccountIds(context, migrationMap)

            // Widget 数据会在下次刷新时自动更新
            Logger.i("HomeViewModel", "Account data migration completed")
        } catch (e: Exception) {
            Logger.e("HomeViewModel", "Failed to migrate account data", e)
        }
    }

    // ── 全局设置 ──

    fun setRefreshInterval(seconds: Int) {
        widgetPrefs.refreshIntervalSeconds = seconds
        _uiState.value = _uiState.value.copy(refreshIntervalSeconds = seconds)
        // 通知前台 Service 用新间隔重新调度 Handler
        notifyServiceReschedule()
        if (apiKeyManager.hasAccounts()) refreshBalance()
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
        widgetPrefs.alertEnabled = enabled
        _uiState.value = _uiState.value.copy(alertEnabled = enabled)
    }

    fun setAlertThreshold(threshold: Float) {
        widgetPrefs.alertThreshold = threshold
        // 更新阈值后自动解除所有暂停，确保新设置立即生效
        widgetPrefs.clearAllSnooze()
        _uiState.value = _uiState.value.copy(
            alertThreshold = threshold,
            snoozeInfo = widgetPrefs.getSnoozeInfo()
        )
    }

    fun setChangeAlertEnabled(enabled: Boolean) {
        widgetPrefs.changeAlertEnabled = enabled
        _uiState.value = _uiState.value.copy(changeAlertEnabled = enabled)
    }

    fun setChangeAlertThreshold(threshold: Float) {
        widgetPrefs.changeAlertThreshold = threshold
        // 更新阈值后自动解除所有暂停
        widgetPrefs.clearAllSnooze()
        _uiState.value = _uiState.value.copy(
            changeAlertThreshold = threshold,
            snoozeInfo = widgetPrefs.getSnoozeInfo()
        )
    }

    fun setChangeAlertPeriodMinutes(minutes: Int) {
        widgetPrefs.changeAlertPeriodMinutes = minutes
        _uiState.value = _uiState.value.copy(changeAlertPeriodMinutes = minutes)
    }

    fun setSnoozeDurationMinutes(minutes: Int) {
        widgetPrefs.snoozeDurationMinutes = minutes
        _uiState.value = _uiState.value.copy(snoozeDurationMinutes = minutes)
    }

    fun clearAllSnooze() {
        widgetPrefs.clearAllSnooze()
        _uiState.value = _uiState.value.copy(snoozeInfo = widgetPrefs.getSnoozeInfo())
    }

    fun refreshSnoozeInfo() {
        _uiState.value = _uiState.value.copy(snoozeInfo = widgetPrefs.getSnoozeInfo())
    }

    // ── 配置导入/导出 ──

    /** 获取配置 JSON 字符串（供导出使用） */
    fun getConfigJson(): String {
        return ConfigManager.buildConfig(getApplication(), apiKeyManager, widgetPrefs)
    }

    /** 应用导入的配置并刷新全部 UI 状态 */
    fun applyImportedConfig(config: AppConfig) {
        val skipped = ConfigManager.applyConfig(config, apiKeyManager, widgetPrefs)
        loadAccounts()
        val importMsg = if (skipped > 0) {
            getApplication<android.app.Application>().getString(R.string.data_config_import_skipped, skipped)
        } else null
        _uiState.value = _uiState.value.copy(
            accounts = apiKeyManager.getAccounts(),
            accountBalances = emptyMap(),
            errorMessage = importMsg ?: _uiState.value.errorMessage,
            refreshIntervalSeconds = widgetPrefs.refreshIntervalSeconds,
            alertEnabled = widgetPrefs.alertEnabled,
            alertThreshold = widgetPrefs.alertThreshold,
            changeAlertEnabled = widgetPrefs.changeAlertEnabled,
            changeAlertThreshold = widgetPrefs.changeAlertThreshold,
            changeAlertPeriodMinutes = widgetPrefs.changeAlertPeriodMinutes,
            snoozeInfo = widgetPrefs.getSnoozeInfo(),
            snoozeDurationMinutes = widgetPrefs.snoozeDurationMinutes
        )
        // 导入后立即刷新余额
        if (apiKeyManager.hasAccounts()) refreshBalance()
    }

    // ── 刷新单个账户 ──

    fun refreshSingleAccount(accountId: String) {
        val account = apiKeyManager.getAccount(accountId) ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val now = System.currentTimeMillis()

            try {
                // 根据供应商类型选择不同的API调用方式
                val result = if (account.providerType == ProviderType.DEEPSEEK) {
                    // DeepSeek供应商：使用原有的BalanceRepository
                    val deepSeekResult = repository.fetchBalance(account.apiKey, account.id)
                    if (deepSeekResult.isSuccess) {
                        val response = deepSeekResult.getOrThrow()
                        ProviderResult.Success(
                            UnifiedBalance(
                                provider = account.providerType,
                                accountId = account.id,
                                isAvailable = true,
                                balances = response.balanceInfos.map { info ->
                                    BalanceEntry(
                                        currency = info.currency,
                                        totalBalance = info.totalBalance.toDoubleOrNull() ?: 0.0,
                                        unit = "元",
                                        grantedBalance = info.grantedBalance.toDoubleOrNull() ?: 0.0,
                                        toppedUpBalance = info.toppedUpBalance.toDoubleOrNull() ?: 0.0
                                    )
                                },
                                isEstimated = false
                            )
                        )
                    } else {
                        val exception = deepSeekResult.exceptionOrNull() ?: IOException("查询失败")
                        ProviderResult.Failure(
                            ProviderError.NetworkError(account.providerType, exception)
                        )
                    }
                } else {
                    // 其他供应商：使用 ProviderFactory 获取对应供应商实例
                    val provider = if (account.providerType == ProviderType.CUSTOM) {
                        val baseUrl = account.extraSettings["baseUrl"] ?: ""
                        ProviderFactory.get(account.providerType, baseUrl)
                    } else {
                        ProviderFactory.get(account.providerType)
                    }

                    // 将usageScript添加到settings中
                    val settings = account.extraSettings.toMutableMap()
                    if (account.usageScript != null) {
                        settings["usageScript"] = account.usageScript
                    }

                    val config = ProviderConfig(
                        providerType = account.providerType,
                        credentials = mapOf(
                            "apiKey" to account.apiKey,
                            "accountId" to account.id,
                            "accountLabel" to account.label
                        ),
                        settings = settings
                    )

                    provider.getBalance(config)
                }

                when (result) {
                    is ProviderResult.Success -> {
                        val balance = result.data
                        val balanceResponse = BalanceResponse(
                            isAvailable = balance.isAvailable,
                            balanceInfos = balance.balances.map { entry ->
                                com.balancesentinel.app.data.model.BalanceInfo(
                                    currency = entry.currency,
                                    totalBalance = entry.totalBalance.toString(),
                                    grantedBalance = entry.grantedBalance.toString(),
                                    toppedUpBalance = entry.toppedUpBalance.toString()
                                )
                            }
                        )

                        // 更新余额
                        val currentBalances = _uiState.value.accountBalances.toMutableMap()
                        currentBalances[account.id] = balanceResponse
                        _uiState.value = _uiState.value.copy(
                            accountBalances = currentBalances,
                            lastRefreshTime = now
                        )

                        // 先清除该账户的旧缓存数据，再保存新数据
                        BalanceWidgetDataStore.removeAccountBalance(getApplication(), account.id)

                        // 同步 Widget 缓存
                        for (info in balanceResponse.balanceInfos) {
                            BalanceWidgetDataStore.saveAccountBalance(
                                context = getApplication(),
                                accountId = account.id,
                                label = account.label,
                                totalBalance = info.totalBalance,
                                currency = info.currency,
                                isAvailable = balanceResponse.isAvailable,
                                grantedBalance = info.grantedBalance,
                                toppedUpBalance = info.toppedUpBalance
                            )
                        }
                    }
                    is ProviderResult.Failure -> {
                        val errorMsg = when (val error = result.error) {
                            is ProviderError.NetworkError -> error.message
                            is ProviderError.ApiUnavailableError -> error.message
                            is ProviderError.AuthError -> error.message
                            else -> "查询失败"
                        }
                        _uiState.value = _uiState.value.copy(errorMessage = "[$account.label] $errorMsg")
                    }
                }
            } catch (e: Exception) {
                Logger.e("HomeViewModel", "refreshSingleAccount failed for ${account.label}", e)
                _uiState.value = _uiState.value.copy(errorMessage = "[$account.label] ${e.message ?: "查询失败"}")
            }

            _uiState.value = _uiState.value.copy(isLoading = false)
            updateAllWidgets()
        }
    }

    // ── 刷新余额（遍历所有账户） ──

    fun refreshBalance() {
        val accounts = apiKeyManager.getAccounts()
        if (accounts.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = getApplication<Application>().getString(R.string.no_key)
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val now = System.currentTimeMillis()
            val newBalances = mutableMapOf<String, BalanceResponse?>()
            var firstError: String? = null

            for (account in accounts) {
                try {
                    // 根据供应商类型选择不同的API调用方式
                    val result = if (account.providerType == ProviderType.DEEPSEEK) {
                        // DeepSeek供应商：使用原有的BalanceRepository
                        val deepSeekResult = repository.fetchBalance(account.apiKey, account.id)
                        if (deepSeekResult.isSuccess) {
                            val response = deepSeekResult.getOrThrow()
                            ProviderResult.Success(
                                UnifiedBalance(
                                    provider = account.providerType,
                                    accountId = account.id,
                                    isAvailable = true,
                                    balances = response.balanceInfos.map { info ->
                                        BalanceEntry(
                                            currency = info.currency,
                                            totalBalance = info.totalBalance.toDoubleOrNull() ?: 0.0,
                                            unit = "元",
                                            grantedBalance = info.grantedBalance.toDoubleOrNull() ?: 0.0,
                                            toppedUpBalance = info.toppedUpBalance.toDoubleOrNull() ?: 0.0
                                        )
                                    },
                                    isEstimated = false
                                )
                            )
                        } else {
                            val exception = deepSeekResult.exceptionOrNull() ?: IOException("查询失败")
                            ProviderResult.Failure(
                                ProviderError.NetworkError(
                                    account.providerType,
                                    exception
                                )
                            )
                        }
                    } else {
                        // 其他供应商：使用 ProviderFactory 获取对应供应商实例
                        val provider = if (account.providerType == ProviderType.CUSTOM) {
                            val baseUrl = account.extraSettings["baseUrl"] ?: ""
                            ProviderFactory.get(account.providerType, baseUrl)
                        } else {
                            ProviderFactory.get(account.providerType)
                        }

                        // 将usageScript添加到settings中
                        val settings = account.extraSettings.toMutableMap()
                        val scriptToUse = account.usageScript
                        Logger.i("HomeViewModel", "Refreshing configured custom-provider usage")
                        if (scriptToUse != null) {
                            settings["usageScript"] = scriptToUse
                            Logger.i("HomeViewModel", "Added usageScript to settings")
                        }

                        val config = ProviderConfig(
                            providerType = account.providerType,
                            credentials = mapOf(
                                "apiKey" to account.apiKey,
                                "accountId" to account.id,
                                "accountLabel" to account.label
                            ),
                            settings = settings
                        )

                        provider.getBalance(config)
                    }

                    when (result) {
                        is ProviderResult.Success -> {
                            val balance = result.data
                            Logger.i("HomeViewModel", "Provider success: isAvailable=${balance.isAvailable}, balances=${balance.balances.size}")
                            // 转换为 BalanceResponse 格式
                            val balanceResponse = BalanceResponse(
                                isAvailable = balance.isAvailable,
                                balanceInfos = balance.balances.map { entry ->
                                    com.balancesentinel.app.data.model.BalanceInfo(
                                        currency = entry.currency,
                                        totalBalance = entry.totalBalance.toString(),
                                        grantedBalance = entry.grantedBalance.toString(),
                                        toppedUpBalance = entry.toppedUpBalance.toString()
                                    )
                                }
                            )
                            Logger.i("HomeViewModel", "BalanceResponse: isAvailable=${balanceResponse.isAvailable}, balanceInfos=${balanceResponse.balanceInfos.size}")

                            // 添加调试信息到 ApiDebugStore
                            val debugEntry = com.balancesentinel.app.data.debug.ApiDebugEntry(
                                accountId = account.id,
                                url = "Provider结果",
                                method = "PROVIDER",
                                requestHeaders = emptyMap(),
                                requestBody = "isAvailable=${balance.isAvailable}",
                                statusCode = 200,
                                responseHeaders = emptyMap(),
                                responseBody = "isAvailable=${balanceResponse.isAvailable}, balanceInfos=${balanceResponse.balanceInfos.size}",
                                timestamp = System.currentTimeMillis(),
                                duration = 0,
                                error = null,
                                accountLabel = account.label,
                                providerType = account.providerType.displayName,
                                baseUrl = account.extraSettings["baseUrl"],
                                endpoint = "Provider结果",
                                isCustomScript = account.usageScript != null,
                                scriptPreview = "isAvailable=${balance.isAvailable}, balances=${balance.balances.size}"
                            )
                            com.balancesentinel.app.data.debug.ApiDebugStore.addEntry(debugEntry)

                            newBalances[account.id] = balanceResponse

                            // 先清除该账户的旧缓存数据，再保存新数据
                            BalanceWidgetDataStore.removeAccountBalance(getApplication(), account.id)

                            // 同步 Widget 缓存
                            for (info in balanceResponse.balanceInfos) {
                                BalanceWidgetDataStore.saveAccountBalance(
                                    context = getApplication(),
                                    accountId = account.id,
                                    label = account.label,
                                    totalBalance = info.totalBalance,
                                    currency = info.currency,
                                    isAvailable = balanceResponse.isAvailable,
                                    grantedBalance = info.grantedBalance,
                                    toppedUpBalance = info.toppedUpBalance
                                )

                                // 写日志
                                RefreshLogStore.addEntry(getApplication(), RefreshLogEntry(
                                    id = now,
                                    type = RefreshLogType.MANUAL,
                                    totalBalance = info.totalBalance,
                                    currency = info.currency,
                                    isAvailable = balanceResponse.isAvailable,
                                    grantedBalance = info.grantedBalance,
                                    toppedUpBalance = info.toppedUpBalance,
                                    timestamp = now,
                                    message = account.label
                                ))

                                // 写 RawRecord
                                RawRecordStore.addRecord(getApplication(), RawRecord(
                                    accountId = account.id,
                                    timestamp = now,
                                    currency = info.currency,
                                    totalBalance = info.totalBalance.toFloatOrNull() ?: 0f,
                                    grantedBalance = info.grantedBalance.toFloatOrNull() ?: 0f,
                                    toppedUpBalance = info.toppedUpBalance.toFloatOrNull() ?: 0f
                                ))

                                // 每账户预警检查
                                AlertChecker.check(
                                    getApplication(), account.id,
                                    info.totalBalance, info.currency, account.label
                                )
                                AlertChecker.checkChange(
                                    getApplication(), account.id,
                                    info.totalBalance, info.currency, account.label
                                )
                            }
                        }
                        is ProviderResult.Failure -> {
                            newBalances[account.id] = null
                            val errorMsg = when (val error = result.error) {
                                is ProviderError.NetworkError -> error.message
                                is ProviderError.ApiUnavailableError -> error.message
                                is ProviderError.AuthError -> error.message
                                else -> "查询失败"
                            }
                            if (firstError == null) firstError = "[${account.label}] $errorMsg"
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("HomeViewModel", "refreshBalance failed for ${account.label}", e)
                    newBalances[account.id] = null
                    if (firstError == null) firstError = "[${account.label}] ${e.message ?: "查询失败"}"
                }
            }

            // 拉取用量统计（异步，失败不影响余额刷新）
            for (account in accounts) {
                try {
                    if (account.providerType == ProviderType.DEEPSEEK) {
                        // DeepSeek供应商：使用原有的BalanceRepository
                        val result = repository.fetchUsage(account.apiKey, account.id)
                        result.onSuccess { usage ->
                            UsageDataStore.saveSnapshot(getApplication(), UsageSnapshot(
                                accountId = account.id,
                                timestamp = now,
                                records = usage.data
                            ))
                        }
                    } else {
                        // 其他供应商：使用 ProviderFactory 获取对应供应商实例
                        val provider = if (account.providerType == ProviderType.CUSTOM) {
                            val baseUrl = account.extraSettings["baseUrl"] ?: ""
                            ProviderFactory.get(account.providerType, baseUrl)
                        } else {
                            ProviderFactory.get(account.providerType)
                        }

                        val config = ProviderConfig(
                            providerType = account.providerType,
                            credentials = mapOf("apiKey" to account.apiKey, "accountId" to account.id),
                            settings = account.extraSettings
                        )

                        val result = provider.getUsage(config)
                        when (result) {
                            is ProviderResult.Success -> {
                                UsageDataStore.saveSnapshot(getApplication(), UsageSnapshot(
                                    accountId = account.id,
                                    timestamp = now,
                                    records = emptyList() // 其他供应商可能不支持用量API
                                ))
                            }
                            is ProviderResult.Failure -> {
                                // 用量查询失败不影响余额刷新
                                Logger.w("HomeViewModel", "Usage query failed for ${account.label}: ${result.error.message}")
                            }
                        }
                    }
                } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                accountBalances = newBalances,
                lastRefreshTime = now,
                errorMessage = firstError
            )

            updateAllWidgets()
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
                RefreshLogStore.addEntry(app, RefreshLogEntry(
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
                missed.forEach { RefreshLogStore.addEntry(app, it) }
            }
        } catch (e: Exception) { Logger.w("HomeViewModel", "operation failed", e) }
    }
}
