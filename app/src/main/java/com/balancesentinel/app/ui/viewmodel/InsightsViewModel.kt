package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.engine.DailyBillReport
import com.balancesentinel.app.data.engine.DailyEngine
import com.balancesentinel.app.data.engine.DailyInput
import com.balancesentinel.app.data.engine.DailyOutput
import com.balancesentinel.app.data.engine.DailyPoint
import com.balancesentinel.app.data.engine.DepletionEstimate
import com.balancesentinel.app.data.engine.IntradayBillReport
import com.balancesentinel.app.data.engine.IntradayEngine
import com.balancesentinel.app.data.engine.IntradayInput
import com.balancesentinel.app.data.engine.IntradayOutput
import com.balancesentinel.app.data.engine.IntradayPoint
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

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
    val dailyOutput: DailyOutput? = null,

    val chartMode: String = "balance",
    val historyVisibleCount: Int = 7,
    val expandedDate: String? = null
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
 *
 * 多账户全部账户模式：null accountId 时逐账户跑引擎再合并。
 */
class InsightsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private val apiKeyManager = ApiKeyManager(application)

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                historyVisibleCount = 7,
                expandedDate = null
            )

            try {
                val summaries = DailySummaryStore.getSummaries(getApplication())
                val allRaw = RawRecordStore.getAllRecords(getApplication())
                val currencies = (summaries.map { it.currency } + allRaw.map { it.currency }).distinct()

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
                val accountId = _uiState.value.selectedAccountId
                val rangeDays = _uiState.value.rangeDays

                // ── Intraday: 24h 滑动窗口 ──
                val cutoff = System.currentTimeMillis() - 24 * 3600_000L
                val recentRaw = RawRecordStore.getRecordsSince(getApplication(), cutoff)
                val intradayOutput = computeIntraday(recentRaw, currency, accountId, accounts)

                // ── Daily: 长期日历天视图 ──
                val todayRaw = RawRecordStore.getTodayRecords(getApplication())
                val dailyOutput = computeDaily(summaries, todayRaw, currency, accountId, accounts, rangeDays)

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

    // ═══════════════════════════════════════════════════════════
    // 计算辅助：单账户走引擎，null=全部账户走逐账户引擎+合并
    // ═══════════════════════════════════════════════════════════

    private fun computeIntraday(
        records: List<RawRecord>,
        currency: String,
        accountId: String?,
        accounts: List<AccountInfo>
    ): IntradayOutput {
        // 单账户或无账户数据 → 直接调用引擎
        if (accountId != null || accounts.isEmpty()) {
            return IntradayEngine.compute(IntradayInput(records, currency, accountId))
        }
        // 全部账户模式 → 逐账户引擎 + 合并
        val outputs = accounts.map { account ->
            IntradayEngine.compute(IntradayInput(records, currency, account.id))
        }
        return mergeIntradayOutputs(outputs)
    }

    private fun computeDaily(
        summaries: List<DailySummary>,
        todayRaw: List<RawRecord>,
        currency: String,
        accountId: String?,
        accounts: List<AccountInfo>,
        rangeDays: Int
    ): DailyOutput {
        if (accountId != null || accounts.isEmpty()) {
            return DailyEngine.compute(DailyInput(summaries, todayRaw, currency, accountId, rangeDays))
        }
        val outputs = accounts.map { account ->
            DailyEngine.compute(DailyInput(summaries, todayRaw, currency, account.id, rangeDays))
        }
        return mergeDailyOutputs(outputs, rangeDays)
    }

    fun selectCurrency(currency: String) {
        _uiState.value = _uiState.value.copy(selectedCurrency = currency)
        loadData()
    }

    fun selectAccount(accountId: String?) {
        // 不再 fallback 到首个账户 — null 即全部账户，走合并路径
        _uiState.value = _uiState.value.copy(selectedAccountId = accountId)
        loadData()
    }

    fun setRangeDays(days: Int) {
        if (_uiState.value.rangeDays == days) return
        _uiState.value = _uiState.value.copy(rangeDays = days)
        loadData()
    }

    fun setChartMode(mode: String) {
        _uiState.value = _uiState.value.copy(chartMode = mode)
    }

    fun loadMoreHistory() {
        val current = _uiState.value
        val maxDays = current.dailyOutput?.dailyPoints?.size ?: 0
        val next = (current.historyVisibleCount + 10).coerceAtMost(maxDays)
        _uiState.value = current.copy(historyVisibleCount = next)
    }

    fun toggleExpandDate(date: String) {
        val current = _uiState.value
        _uiState.value = current.copy(
            expandedDate = if (current.expandedDate == date) null else date
        )
    }

    companion object {
        /**
         * 合并多账户 Intraday 输出。
         *
         * 同一时间戳（1s 容差）的跨账户点合并：余额/充值/赠送求和，
         * 再按自适应 minInterval 去重，Bill report 对各账户求和。
         */
        fun mergeIntradayOutputs(outputs: List<IntradayOutput>): IntradayOutput {
            if (outputs.isEmpty()) return IntradayOutput(
                emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0
            )
            if (outputs.size == 1) return outputs[0]

            // 收集所有点，按时间排序
            val allPoints = outputs.flatMap { it.trendPoints }.sortedBy { it.timestamp }

            // 第一步：同一秒内的点合并（余额/充值/赠送求和）
            val mergedByTime = mutableListOf<IntradayPoint>()
            var i = 0
            while (i < allPoints.size) {
                val groupTs = allPoints[i].timestamp
                var sumBalance = 0f
                var sumTopUp = 0f
                var sumGrant = 0f
                var hasTopUp = false
                var hasGrant = false

                while (i < allPoints.size && allPoints[i].timestamp - groupTs < 1000L) {
                    val p = allPoints[i]
                    sumBalance += p.actualBalance
                    sumTopUp += p.topUpAmount
                    sumGrant += p.grantAmount
                    if (p.isTopUp) hasTopUp = true
                    if (p.isGrant) hasGrant = true
                    i++
                }
                mergedByTime.add(
                    IntradayPoint(groupTs, sumBalance, hasTopUp, hasGrant, sumTopUp, sumGrant)
                )
            }

            // 第二步：自适应间隔去重
            val minInterval = when {
                mergedByTime.size <= 20 -> 0L
                mergedByTime.size <= 60 -> 15_000L
                else -> 30_000L
            }

            val merged = if (minInterval == 0L || mergedByTime.isEmpty()) {
                mergedByTime
            } else {
                val result = mutableListOf(mergedByTime[0])
                for (j in 1 until mergedByTime.size) {
                    if (mergedByTime[j].timestamp - result.last().timestamp >= minInterval) {
                        result.add(mergedByTime[j])
                    }
                }
                // 确保尾部不丢点
                if (result.last().timestamp != mergedByTime.last().timestamp) {
                    result.add(mergedByTime.last())
                }
                result
            }

            val totalConsumed = outputs.sumOf { it.billReport.consumed.toDouble() }.toFloat()
            val totalToppedUp = outputs.sumOf { it.billReport.toppedUp.toDouble() }.toFloat()
            val totalGranted = outputs.sumOf { it.billReport.granted.toDouble() }.toFloat()

            return IntradayOutput(
                trendPoints = merged,
                billReport = IntradayBillReport(
                    consumed = totalConsumed,
                    toppedUp = totalToppedUp,
                    granted = totalGranted,
                    netChange = totalToppedUp + totalGranted - totalConsumed
                ),
                dataPointCount = merged.size
            )
        }

        /**
         * 合并多账户 Daily 输出。
         *
         * 按日期合并 dailyPoints：同一天的各账户数据求和（balance/open/consumed/toppedUp/granted），
         * Bill report 对各账户求和。
         */
        fun mergeDailyOutputs(outputs: List<DailyOutput>, rangeDays: Int): DailyOutput {
            if (outputs.isEmpty()) return DailyOutput(
                emptyList(), DailyBillReport(0f, 0f, 0f, 0f, ""), null, "", true, true
            )
            if (outputs.size == 1) return outputs[0]

            val dateMap = linkedMapOf<String, DailyPoint>()
            for (output in outputs) {
                for (point in output.dailyPoints) {
                    val existing = dateMap[point.date]
                    if (existing == null) {
                        dateMap[point.date] = point
                    } else {
                        dateMap[point.date] = DailyPoint(
                            date = point.date,
                            balance = existing.balance + point.balance,
                            consumed = existing.consumed + point.consumed,
                            toppedUp = existing.toppedUp + point.toppedUp,
                            granted = existing.granted + point.granted,
                            isGapFill = existing.isGapFill && point.isGapFill,
                            open = existing.open + point.open,
                            sampleCount = maxOf(existing.sampleCount, point.sampleCount)
                        )
                    }
                }
            }

            val merged = dateMap.values.toList()
            val periodLabel = outputs.firstOrNull()?.periodLabel ?: ""

            val totalConsumed = merged.sumOf { it.consumed.toDouble() }.toFloat()
            val totalToppedUp = merged.sumOf { it.toppedUp.toDouble() }.toFloat()
            val totalGranted = merged.sumOf { it.granted.toDouble() }.toFloat()

            // 消耗预估沿用首个有效输出（多账户合并后线性回归需重新计算，暂用首个非空）
            val estimate = outputs.firstOrNull { it.estimate != null }?.estimate

            val withConsumption = merged.filter { it.consumed > 0f }
            val insufficientData = withConsumption.size < 3

            return DailyOutput(
                dailyPoints = merged,
                billReport = DailyBillReport(
                    consumed = totalConsumed,
                    toppedUp = totalToppedUp,
                    granted = totalGranted,
                    netChange = totalToppedUp + totalGranted - totalConsumed,
                    periodLabel = periodLabel
                ),
                estimate = estimate,
                periodLabel = periodLabel,
                isEmpty = merged.isEmpty(),
                insufficientData = insufficientData
            )
        }
    }
}
