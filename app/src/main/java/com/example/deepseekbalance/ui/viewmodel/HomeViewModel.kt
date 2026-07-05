package com.example.deepseekbalance.ui.viewmodel

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import com.example.deepseekbalance.data.util.Logger
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.deepseekbalance.CrashLogger
import com.example.deepseekbalance.data.model.AccountInfo
import com.example.deepseekbalance.data.model.BalanceInfo
import com.example.deepseekbalance.data.model.BalanceResponse
import com.example.deepseekbalance.data.model.RefreshLogEntry
import com.example.deepseekbalance.data.model.RefreshLogType
import com.example.deepseekbalance.data.model.RawRecord
import com.example.deepseekbalance.data.repository.AlertChecker
import com.example.deepseekbalance.data.repository.ApiKeyManager
import com.example.deepseekbalance.data.repository.AppConfig
import com.example.deepseekbalance.data.repository.BalanceRepository
import com.example.deepseekbalance.data.repository.ConfigManager
import com.example.deepseekbalance.data.repository.CleanupScheduler
import com.example.deepseekbalance.data.repository.MidnightScheduler
import com.example.deepseekbalance.data.repository.RawRecordStore
import com.example.deepseekbalance.data.repository.RefreshLogStore
import com.example.deepseekbalance.data.repository.RefreshScheduler
import com.example.deepseekbalance.data.repository.WidgetPrefs
import com.example.deepseekbalance.R
import com.example.deepseekbalance.data.model.UsageSnapshot
import com.example.deepseekbalance.data.repository.UsageDataStore
import com.example.deepseekbalance.service.BalanceRefreshService
import com.example.deepseekbalance.widget.BalanceWidgetDataStore
import com.example.deepseekbalance.widget.StaticWidgetProvider_2x1
import com.example.deepseekbalance.widget.StaticWidgetProvider_2x2
import com.example.deepseekbalance.widget.StaticWidgetProvider_3x1
import com.example.deepseekbalance.widget.StaticWidgetProvider_4x2
import com.example.deepseekbalance.widget.StaticWidgetProvider_5x1
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
    val statusSummary: com.example.deepseekbalance.data.repository.StatusSummary? = null,
    val refreshIntervalSeconds: Int = WidgetPrefs.DEFAULT_INTERVAL,
    val alertEnabled: Boolean = false,
    val alertThreshold: Float = 0f,
    val changeAlertEnabled: Boolean = false,
    val changeAlertThreshold: Float = 0f,
    val changeAlertPeriodMinutes: Int = 0,
    val snoozeInfo: com.example.deepseekbalance.data.repository.SnoozeInfo = com.example.deepseekbalance.data.repository.SnoozeInfo(),
    val snoozeDurationMinutes: Int = 60
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BalanceRepository = BalanceRepository()
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(application)
    private val widgetPrefs: WidgetPrefs = WidgetPrefs(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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
            if (accounts.isEmpty()) return

            val allBalances = BalanceWidgetDataStore.getAllBalances(getApplication())
            val byAccount = allBalances.groupBy { it.accountId }

            val accountBalances = mutableMapOf<String, BalanceResponse>()
            for (account in accounts) {
                val entries = byAccount[account.id] ?: continue
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
            }

            if (accountBalances.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    accountBalances = accountBalances,
                    lastRefreshTime = allBalances.maxOfOrNull { it.lastUpdated } ?: 0L
                )
            }
        } catch (_: Exception) {}
    }

    // ── 午夜调度 ──

    private fun scheduleMidnightAndCheckSummary() {
        try {
            MidnightScheduler.schedule(getApplication())
            viewModelScope.launch {
                CleanupScheduler.runCleanup(getApplication())
            }
        } catch (_: Exception) {}
    }

    // ── 崩溃日志 ──

    fun loadCrashLogs() {
        try {
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(crashLogs = CrashLogger.getCrashes(app))
        } catch (_: Exception) {}
    }

    fun clearCrashes() {
        try {
            CrashLogger.clear(getApplication())
            _uiState.value = _uiState.value.copy(crashLogs = emptyList())
        } catch (_: Exception) {}
    }

    // ── 账户管理 ──

    fun addAccount(label: String, apiKey: String) {
        if (label.isBlank() || apiKey.isBlank()) return
        val account = apiKeyManager.addAccount(label, apiKey)
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

    fun renameAccount(id: String, newLabel: String) {
        if (newLabel.isBlank()) return
        apiKeyManager.renameAccount(id, newLabel)
        loadAccounts()
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
        } catch (_: Exception) {}
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
        ConfigManager.applyConfig(config, apiKeyManager, widgetPrefs)
        loadAccounts()
        _uiState.value = _uiState.value.copy(
            accounts = apiKeyManager.getAccounts(),
            accountBalances = emptyMap(),
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
                    val result = repository.fetchBalance(account.apiKey)
                    result.fold(
                        onSuccess = { response ->
                            newBalances[account.id] = response

                            // 同步 Widget 缓存
                            for (info in response.balanceInfos) {
                                BalanceWidgetDataStore.saveAccountBalance(
                                    context = getApplication(),
                                    accountId = account.id,
                                    label = account.label,
                                    totalBalance = info.totalBalance,
                                    currency = info.currency,
                                    isAvailable = response.isAvailable,
                                    grantedBalance = info.grantedBalance,
                                    toppedUpBalance = info.toppedUpBalance
                                )

                                // 写日志
                                RefreshLogStore.addEntry(getApplication(), RefreshLogEntry(
                                    id = now,
                                    type = RefreshLogType.MANUAL,
                                    totalBalance = info.totalBalance,
                                    currency = info.currency,
                                    isAvailable = response.isAvailable,
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
                        },
                        onFailure = { e ->
                            newBalances[account.id] = null
                            if (firstError == null) firstError = "[${account.label}] ${e.message ?: getApplication<Application>().getString(R.string.api_error_request_fail)}"
                        }
                    )
                } catch (e: Exception) {
                    Logger.e("HomeViewModel", "refreshBalance failed for ${account.label}", e)
                    newBalances[account.id] = null
                }
            }

            // 拉取用量统计（异步，失败不影响余额刷新）
            for (account in accounts) {
                try {
                    val result = repository.fetchUsage(account.apiKey)
                    result.onSuccess { usage ->
                        UsageDataStore.saveSnapshot(getApplication(), UsageSnapshot(
                            accountId = account.id,
                            timestamp = now,
                            records = usage.data
                        ))
                    }
                } catch (_: Exception) {}
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
                } catch (_: Exception) {}
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
        } catch (_: Exception) {}
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
        } catch (_: Exception) {}
    }
}
