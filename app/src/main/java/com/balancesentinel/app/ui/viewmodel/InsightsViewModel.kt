package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.engine.DailyEngine
import com.balancesentinel.app.data.engine.DailyInput
import com.balancesentinel.app.data.engine.DailyOutput
import com.balancesentinel.app.data.engine.IntradayEngine
import com.balancesentinel.app.data.engine.IntradayInput
import com.balancesentinel.app.data.engine.IntradayOutput
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 洞察 UI 状态 — 双引擎输出。
 * [intradayOutput] 来自 IntradayEngine（24h 滑动窗口），
 * [dailyOutput] 来自 DailyEngine（长期日历天视图）。
 */
data class InsightsUiState(
    val isLoading: Boolean = false,
    val accounts: List<AccountInfo> = emptyList(),
    val selectedAccountId: String? = null,
    val availableCurrencies: List<String> = emptyList(),
    val selectedCurrency: String = "",
    val rangeDays: Int = 7,

    /** IntradayEngine 输出 — 24h 滑动窗口 */
    val intradayOutput: IntradayOutput? = null,
    /** DailyEngine 输出 — 长期日历天视图 */
    val dailyOutput: DailyOutput? = null
) {
    val isEmpty: Boolean
        get() = (intradayOutput?.dataPointCount ?: 0) == 0 &&
                (dailyOutput?.isEmpty ?: true)
}

/**
 * 洞察 ViewModel — 双引擎编排层。
 *
 * 读存储 → 分别构造 IntradayInput / DailyInput →
 * 调用 IntradayEngine.compute() / DailyEngine.compute() → 更新 UI state。
 */
class InsightsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private val apiKeyManager = ApiKeyManager(application)

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val summaries = DailySummaryStore.getSummaries(getApplication())
                val allRaw = RawRecordStore.getAllRecords(getApplication())
                val currencies = (summaries.map { it.currency } + allRaw.map { it.currency }).distinct()

                // Account info may fail when keystore is unavailable (e.g. test env);
                // degrade gracefully with empty account list.
                val accounts = try {
                    apiKeyManager.migrateLegacyKeyIfNeeded()
                    apiKeyManager.getAccounts()
                } catch (_: Exception) {
                    emptyList()
                }

                val currency = _uiState.value.selectedCurrency.let {
                    if (it.isNotEmpty() && currencies.contains(it)) it
                    else currencies.firstOrNull() ?: ""
                }
                // 多账户场景：null 时自动选中首个账户，避免引擎层 filterAccountId==null
                // 短路导致多账户记录混合（per-pair 分析不能跨账户）
                val accountId = _uiState.value.selectedAccountId
                    ?: accounts.firstOrNull()?.id
                val rangeDays = _uiState.value.rangeDays

                // ── Intraday: 24h 滑动窗口 ──
                val cutoff = System.currentTimeMillis() - 24 * 3600_000L
                val recentRaw = RawRecordStore.getRecordsSince(getApplication(), cutoff)
                val intradayInput = IntradayInput(recentRaw, currency, accountId)
                val intradayOutput = IntradayEngine.compute(intradayInput)

                // ── Daily: 长期日历天视图 ──
                val todayRaw = RawRecordStore.getTodayRecords(getApplication())
                val dailyInput = DailyInput(summaries, todayRaw, currency, accountId, rangeDays)
                val dailyOutput = DailyEngine.compute(dailyInput)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    accounts = accounts,
                    selectedAccountId = accountId,
                    availableCurrencies = currencies,
                    selectedCurrency = currency,
                    intradayOutput = intradayOutput,
                    dailyOutput = dailyOutput
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun selectCurrency(currency: String) {
        _uiState.value = _uiState.value.copy(selectedCurrency = currency)
        loadData()
    }

    fun selectAccount(accountId: String?) {
        // null → 自动选中首个账户（引擎 per-pair 分析不支持跨账户混合）
        val resolved = accountId ?: _uiState.value.accounts.firstOrNull()?.id
        _uiState.value = _uiState.value.copy(selectedAccountId = resolved)
        loadData()
    }

    fun setRangeDays(days: Int) {
        if (_uiState.value.rangeDays == days) return
        _uiState.value = _uiState.value.copy(rangeDays = days)
        loadData()
    }
}
