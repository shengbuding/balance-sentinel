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
         * 合并多账户 Intraday 输出（carry-forward 算法）。
         *
         * 在每个唯一时间戳上，维护各账户的最后已知余额并求和，
         * 确保图表始终反映全部账户的余额总和而非交替震荡。
         * 最后用自适应 minInterval 降采样，Bill report 对各账户求和。
         */
        fun mergeIntradayOutputs(outputs: List<IntradayOutput>): IntradayOutput {
            if (outputs.isEmpty()) return IntradayOutput(
                emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0
            )
            if (outputs.size == 1) return outputs[0]

            // 构建每个时间戳 → 哪些账户在此刻有新数据
            val updatesByTs = linkedMapOf<Long, MutableList<Pair<Int, IntradayPoint>>>()
            for ((idx, output) in outputs.withIndex()) {
                for (point in output.trendPoints) {
                    updatesByTs.getOrPut(point.timestamp) { mutableListOf() }
                        .add(idx to point)
                }
            }

            // Carry-forward：按时间推进，维护每个账户的最后已知余额
            val lastPerAccount = mutableMapOf<Int, IntradayPoint>()
            val merged = mutableListOf<IntradayPoint>()

            for ((ts, updates) in updatesByTs) {
                // 更新有变化的账户
                for ((idx, point) in updates) {
                    lastPerAccount[idx] = point
                }
                // 此时刻各账户的余额/充值/赠送求和
                val totalBalance = lastPerAccount.values.sumOf { it.actualBalance.toDouble() }.toFloat()
                val tsTopUpAmt = updates.sumOf { (_, p) -> p.topUpAmount.toDouble() }.toFloat()
                val tsGrantAmt = updates.sumOf { (_, p) -> p.grantAmount.toDouble() }.toFloat()
                val tsHasTopUp = updates.any { (_, p) -> p.isTopUp }
                val tsHasGrant = updates.any { (_, p) -> p.isGrant }

                merged.add(IntradayPoint(ts, totalBalance, tsHasTopUp, tsHasGrant, tsTopUpAmt, tsGrantAmt))
            }

            // 自适应间隔降采样
            val minInterval = when {
                merged.size <= 20 -> 0L
                merged.size <= 60 -> 15_000L
                else -> 30_000L
            }

            val sampled = if (minInterval == 0L || merged.isEmpty()) {
                merged
            } else {
                val result = mutableListOf(merged[0])
                for (j in 1 until merged.size) {
                    if (merged[j].timestamp - result.last().timestamp >= minInterval) {
                        result.add(merged[j])
                    }
                }
                // 始终保留尾点（当前余额依赖它）
                if (result.last().timestamp < merged.last().timestamp) {
                    result.add(merged.last())
                }
                result
            }

            val totalConsumed = outputs.sumOf { it.billReport.consumed.toDouble() }.toFloat()
            val totalToppedUp = outputs.sumOf { it.billReport.toppedUp.toDouble() }.toFloat()
            val totalGranted = outputs.sumOf { it.billReport.granted.toDouble() }.toFloat()

            return IntradayOutput(
                trendPoints = sampled,
                billReport = IntradayBillReport(
                    consumed = totalConsumed,
                    toppedUp = totalToppedUp,
                    granted = totalGranted,
                    netChange = totalToppedUp + totalGranted - totalConsumed
                ),
                dataPointCount = sampled.size
            )
        }

        /**
         * 合并多账户 Daily 输出。
         *
         * 按日期合并 dailyPoints：同一天的各账户数据求和，
         * Bill report 对各账户求和，消耗预估基于合并后的数据重新计算。
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

            // 基于合并后的数据重新计算消耗预估
            val estimate = computeMergedEstimate(merged, rangeDays)

            val withConsumption = merged.filter { it.consumed > 0f }
            val insufficientData = withConsumption.isEmpty()

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

        /**
         * 基于合并后的 dailyPoints 重新计算消耗预估。
         * 对各账户的 dailyRate 求和，用合并后的总余额和总日耗率计算剩余天数。
         */
        private fun computeMergedEstimate(
            points: List<DailyPoint>,
            rangeDays: Int
        ): DepletionEstimate? {
            val withConsumption = points.filter { it.consumed > 0f }
            if (withConsumption.isEmpty()) return null

            val lastBalance = points.lastOrNull()?.balance ?: return null

            val yValues = withConsumption.map { it.consumed }
            val n = withConsumption.size.toFloat()
            val sumY = yValues.sum()
            val meanRate = sumY / n

            if (meanRate <= 0f) return null

            val dailyRate: Float
            val methodLabel: String

            if (withConsumption.size >= 3) {
                val xValues = withConsumption.indices.map { it.toFloat() }
                val sumX = xValues.sum()
                val sumXY = xValues.zip(yValues).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
                val sumX2 = xValues.sumOf { (it * it).toDouble() }.toFloat()
                val denominator = n * sumX2 - sumX * sumX

                if (denominator != 0f) {
                    val slope = (n * sumXY - sumX * sumY) / denominator
                    if (slope > 0f) {
                        dailyRate = slope
                        methodLabel = "基于最近${rangeDays}天多账户消耗数据线性回归"
                    } else {
                        dailyRate = meanRate
                        methodLabel = "基于最近${rangeDays}天多账户平均消耗估算"
                    }
                } else {
                    dailyRate = meanRate
                    methodLabel = "基于最近${rangeDays}天多账户平均消耗估算"
                }
            } else {
                dailyRate = meanRate
                methodLabel = "基于${withConsumption.size}天消耗数据估算"
            }

            val daysRemaining = lastBalance / dailyRate

            val depletionDate = try {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, daysRemaining.roundToInt())
                "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
            } catch (_: Exception) {
                "—"
            }

            return DepletionEstimate(
                dailyRate = dailyRate,
                daysRemaining = daysRemaining,
                depletionDate = depletionDate,
                methodLabel = methodLabel
            )
        }
    }
}
