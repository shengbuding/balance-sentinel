package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.engine.DailyBillReport
import com.balancesentinel.app.data.engine.DailyEngine
import com.balancesentinel.app.data.engine.DailyInput
import com.balancesentinel.app.data.engine.DailyOutput
import com.balancesentinel.app.data.engine.DailyPoint
import com.balancesentinel.app.data.engine.DepletionEstimate
import com.balancesentinel.app.data.engine.EstimateMethod
import com.balancesentinel.app.data.engine.IntradayBillReport
import com.balancesentinel.app.data.engine.IntradayEngine
import com.balancesentinel.app.data.engine.IntradayInput
import com.balancesentinel.app.data.engine.IntradayOutput
import com.balancesentinel.app.data.engine.IntradayPoint
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.api.PERCENTAGE_CURRENCY
import com.balancesentinel.app.data.api.UNKNOWN_QUOTA_REMAINING
import com.balancesentinel.app.data.api.QuotaPeriodSnapshot
import com.balancesentinel.app.data.api.quotaPeriodRank
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.AccountUiRepository
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.data.repository.HistoryRepository
import com.balancesentinel.app.data.repository.RoomHistoryRepository
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 洞察 UI 状态 — 双引擎输出。
 * [intradayOutput] 来自 IntradayEngine（24h 滑动窗口），
 * [dailyOutput] 来自 DailyEngine（长期日历天视图）。
 */
data class InsightsUiState(
    val accountLoadState: AccountLoadState = AccountLoadState.Loading,
    val isLoading: Boolean = false,
    val accounts: List<AccountInfo> = emptyList(),
    /** Accounts that have data for the selected currency in the insight windows. */
    val eligibleAccounts: List<AccountInfo> = emptyList(),
    val selectedAccountId: String? = null,
    val availableCurrencies: List<String> = emptyList(),
    val selectedCurrency: String = "",
    val credentialCorrupt: Boolean = false,
    val rangeDays: Int = 7,

    /** IntradayEngine 输出 — 24h 滑动窗口 */
    val intradayOutput: IntradayOutput? = null,
    /** DailyEngine 输出 — 长期日历天视图（受 rangeDays 控制） */
    val dailyOutput: DailyOutput? = null,
    /** 全量历史日汇总数据 — 不受 rangeDays 影响，独立于趋势图 */
    val dailyHistoryPoints: List<DailyPoint> = emptyList(),

    /** Percentage quota windows; populated only when selectedCurrency is `%`. */
    val quotaInsight: QuotaInsight? = null,

    val chartMode: String = "balance",
    val historyVisibleCount: Int = 7,
    val expandedDate: String? = null
) {
    val isEmpty: Boolean
        get() = quotaInsight?.isEmpty != false &&
                (intradayOutput?.dataPointCount ?: 0) == 0 &&
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
class InsightsViewModel @JvmOverloads constructor(
    application: Application,
    private val injectedAccountUiRepository: AccountUiRepository? = null,
    private val injectedHistoryRepository: HistoryRepository? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(InsightsUiState(
        selectedAccountId = savedStateHandle[KEY_ACCOUNT_ID],
        selectedCurrency = savedStateHandle[KEY_CURRENCY] ?: "",
        rangeDays = savedStateHandle[KEY_RANGE_DAYS] ?: 7
    ))
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private val workScope: CoroutineScope by lazy {
        if (injectedHistoryRepository != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        } else {
            viewModelScope
        }
    }

    private val accountSource: AccountUiRepository = injectedAccountUiRepository
        ?: RoomAccountUiRepository(
            RoomAccountRepository(WalletDatabaseProvider.get(application)),
            EncryptedPreferencesCredentialStore(application)
        )
    private val historySource: HistoryRepository = injectedHistoryRepository
        ?: RoomHistoryRepository(WalletDatabaseProvider.get(application))
    private var accountCollectionJob: Job? = null
    private var loadDataJob: Job? = null
    private val loadGeneration = AtomicLong()

    init {
        observeAccounts()
    }

    private fun observeAccounts() {
        accountCollectionJob?.cancel()
        accountCollectionJob = workScope.launch {
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
                            _uiState.update { current -> current.copy(
                                accountLoadState = state,
                                isLoading = true
                            ) }
                        }
                        is AccountLoadState.Ready -> {
                            _uiState.update { current ->
                                val selected = current.selectedAccountId
                                    ?.takeIf { id ->
                                        state.accounts.isEmpty() || state.accounts.any { it.id == id }
                                    }
                                current.copy(
                                    accountLoadState = state,
                                    accounts = state.accounts,
                                    selectedAccountId = selected,
                                    credentialCorrupt = false
                                )
                            }
                            loadData()
                        }
                        is AccountLoadState.Corrupt -> {
                            _uiState.update { current -> current.copy(
                                accountLoadState = state,
                                credentialCorrupt = true,
                                isLoading = false
                            ) }
                            loadData()
                        }
                    }
                }
        }
    }

    fun loadData() {
        val accounts = when (val state = _uiState.value.accountLoadState) {
            is AccountLoadState.Ready -> state.accounts
            is AccountLoadState.Corrupt -> _uiState.value.accounts
            AccountLoadState.Loading -> return
        }
        loadDataJob?.cancel()
        val generation = loadGeneration.incrementAndGet()
        loadDataJob = workScope.launch(Dispatchers.Default) {
            _uiState.update { current -> current.copy(
                isLoading = true,
                expandedDate = null
            ) }

            try {
                val requestedAccountId = _uiState.value.selectedAccountId
                val requestedCurrency = _uiState.value.selectedCurrency
                val rangeDays = _uiState.value.rangeDays
                val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
                val historyFrom = today.minusDays(365).toString()
                val historyTo = today.toString()
                val historyFromMillis = today.minusDays(365)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                val historyToMillis = today.plusDays(1)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                val allSummaries = historySource.summaries(
                    accountId = null,
                    fromDateInclusive = historyFrom,
                    toDateInclusive = historyTo
                )
                val accountIds = accounts.map { it.id }
                val accountIdSet = accountIds.toHashSet()
                val currentBalances = runCatching {
                    BalanceWidgetDataStore.getAllBalances(getApplication())
                }.getOrDefault(emptyList())
                val currentCurrenciesByAccount = currentBalances
                    .asSequence()
                    .filter { it.accountId in accountIdSet && it.currency.isNotBlank() }
                    .groupBy { it.accountId }
                    .asSequence()
                    .mapNotNull { (accountId, balances) ->
                        val latestUpdatedAt = balances.maxOf { it.lastUpdated }
                        val currencies = balances.asSequence()
                            .filter { it.lastUpdated == latestUpdatedAt }
                            .mapNotNull { balance -> canonicalCurrency(balance.currency) }
                            .toSet()
                        accountId to currencies
                    }
                    .filter { (_, currencies) -> currencies.isNotEmpty() }
                    .toMap()
                val currentCurrencies = currentCurrenciesByAccount.values.flatten()
                val fallbackAccountIds = accountIds.filterNot(currentCurrenciesByAccount::containsKey)
                val fallbackAccountIdSet = fallbackAccountIds.toHashSet()
                val fallbackCurrencies = historySource.distinctCurrencies(
                    accountIds = fallbackAccountIds,
                    fromInclusive = historyFromMillis,
                    toExclusive = historyToMillis
                ) +
                    allSummaries.asSequence()
                        .filter { it.accountId in fallbackAccountIdSet }
                        .mapNotNull { canonicalCurrency(it.currency) }
                        .toList()
                val currencies = (currentCurrencies + fallbackCurrencies)
                    .asSequence()
                    .mapNotNull(::canonicalCurrency)
                    .distinct()
                    .sorted()
                    .toList()
                val currency = requestedCurrency.let {
                    if (it.isNotEmpty() && currencies.contains(it)) it
                    else currencies.firstOrNull() ?: ""
                }
                if (generation != loadGeneration.get()) return@launch
                if (currency != requestedCurrency) {
                    savedStateHandle[KEY_CURRENCY] = currency
                }
                val selectedAccountSupportsCurrency = requestedAccountId != null && (
                    currentCurrenciesByAccount[requestedAccountId]?.contains(currency) == true ||
                        allSummaries.any {
                            it.accountId == requestedAccountId &&
                                canonicalCurrency(it.currency) == currency
                        }
                )
                val scopedAccounts = if (selectedAccountSupportsCurrency) {
                    accounts.filter { it.id == requestedAccountId }
                } else {
                    accounts
                }
                val scopedAccountId = scopedAccounts.singleOrNull()?.id

                // ── Intraday: 24h 滑动窗口 ──
                val cutoff = System.currentTimeMillis() - 24 * 3600_000L
                val allRecentRaw = readHistoryWindow(historySource, scopedAccounts, currency, cutoff, Long.MAX_VALUE)
                val eligibleAccountIds = buildSet {
                    currentCurrenciesByAccount.forEach { (accountId, accountCurrencies) ->
                        if (currency in accountCurrencies) add(accountId)
                    }
                    allSummaries.asSequence()
                        .filter {
                            it.accountId in fallbackAccountIdSet &&
                                canonicalCurrency(it.currency) == currency
                        }
                        .mapTo(this) { it.accountId }
                    allRecentRaw.asSequence()
                        .filter {
                            it.accountId in fallbackAccountIdSet &&
                                canonicalCurrency(it.currency) == currency
                        }
                        .mapTo(this) { it.accountId }
                }
                // Keep the filter chips scoped to every account that supports the
                // selected currency. `scopedAccounts` is only the query scope for
                // the selected chart and must not hide the other valid choices.
                val eligibleAccounts = accounts.filter { it.id in eligibleAccountIds }
                val accountId = requestedAccountId?.takeIf { id ->
                    eligibleAccounts.any { it.id == id }
                }
                if (accountId != requestedAccountId) {
                    if (generation != loadGeneration.get()) return@launch
                    savedStateHandle[KEY_ACCOUNT_ID] = accountId
                }
                val recentRaw = if (accountId == null) {
                    allRecentRaw
                } else {
                    allRecentRaw.filter { it.accountId == accountId }
                }

                if (currency == PERCENTAGE_CURRENCY) {
                    val quotaSummaries = summariesForQuota(allSummaries, currency, accountId)
                    val quotaInsight = computeQuotaInsight(
                        rawRecords = recentRaw,
                        summaries = quotaSummaries,
                        balances = currentBalances.filter { balance ->
                            balance.accountId in eligibleAccountIds &&
                                canonicalCurrency(balance.currency) == PERCENTAGE_CURRENCY
                        },
                        accountId = accountId,
                        eligibleAccounts = eligibleAccounts
                    )
                    val todayRaw = recentRaw.filter {
                        Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == today
                    }
                    val dailyOutput = computeDaily(
                        quotaSummaries,
                        todayRaw,
                        currency,
                        accountId,
                        eligibleAccounts,
                        rangeDays
                    )
                    val fullHistoryOutput = computeDaily(
                        quotaSummaries,
                        todayRaw,
                        currency,
                        accountId,
                        eligibleAccounts,
                        365
                    )
                    if (generation != loadGeneration.get()) return@launch
                    _uiState.update { current -> current.copy(
                        isLoading = false,
                        eligibleAccounts = eligibleAccounts,
                        selectedAccountId = accountId,
                        availableCurrencies = currencies,
                        selectedCurrency = currency,
                        intradayOutput = null,
                        dailyOutput = dailyOutput,
                        dailyHistoryPoints = fullHistoryOutput.dailyPoints,
                        quotaInsight = quotaInsight
                    ) }
                    return@launch
                }
                val intradayOutput = computeIntraday(recentRaw, currency, accountId, eligibleAccounts)

                // ── Daily: 长期日历天视图 ──
                val todayRaw = recentRaw.filter {
                    Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == today
                }
                val summaries = allSummaries.filter { summary ->
                    canonicalCurrency(summary.currency) == currency &&
                        (accountId == null || summary.accountId == accountId)
                }
                val dailyOutput = computeDaily(summaries, todayRaw, currency, accountId, eligibleAccounts, rangeDays)

                // ── Daily History: 全量历史日汇总（不受 rangeDays 影响）──
                val fullHistoryOutput = computeDaily(summaries, todayRaw, currency, accountId, eligibleAccounts, 365)

                if (generation != loadGeneration.get()) return@launch
                _uiState.update { current -> current.copy(
                    isLoading = false,
                    eligibleAccounts = eligibleAccounts,
                    selectedAccountId = accountId,
                    availableCurrencies = currencies,
                    selectedCurrency = currency,
                    intradayOutput = intradayOutput,
                    dailyOutput = dailyOutput,
                    dailyHistoryPoints = fullHistoryOutput.dailyPoints,
                    quotaInsight = null
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == loadGeneration.get()) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 计算辅助：单账户走引擎，null=全部账户走逐账户引擎+合并
    // ═══════════════════════════════════════════════════════════

    private suspend fun readHistoryWindow(
        repository: HistoryRepository,
        accounts: List<AccountInfo>,
        currency: String,
        fromInclusive: Long,
        toExclusive: Long
    ): List<RawRecord> {
        if (currency.isBlank()) return emptyList()
        val records = mutableListOf<RawRecord>()
        for (account in accounts) {
            var cursor: com.balancesentinel.app.data.repository.HistoryCursor? = null
            while (true) {
                val page = repository.page(
                    accountId = account.id,
                    currency = currency,
                    fromInclusive = fromInclusive,
                    toExclusive = toExclusive,
                    after = cursor,
                    limit = HistoryRepository.MAX_PAGE_SIZE
                )
                if (page.records.isEmpty()) break
                records += page.records.map { it.value }
                val next = page.nextCursor ?: break
                if (next == cursor) break
                cursor = next
            }
        }
        return records
    }

    override fun onCleared() {
        if (injectedHistoryRepository != null) workScope.cancel()
        super.onCleared()
    }

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
        return if (currency == PERCENTAGE_CURRENCY) {
            mergeQuotaDailyOutputs(outputs)
        } else {
            mergeDailyOutputs(outputs, rangeDays)
        }
    }

    fun selectCurrency(currency: String) {
        savedStateHandle[KEY_CURRENCY] = currency
        _uiState.update { it.copy(selectedCurrency = currency) }
        loadData()
    }

    fun selectAccount(accountId: String?) {
        savedStateHandle[KEY_ACCOUNT_ID] = accountId
        // 不再 fallback 到首个账户 — null 即全部账户，走合并路径
        _uiState.update { it.copy(selectedAccountId = accountId) }
        loadData()
    }

    fun setRangeDays(days: Int) {
        if (_uiState.value.rangeDays == days) return
        savedStateHandle[KEY_RANGE_DAYS] = days

        _uiState.update { it.copy(rangeDays = days) }
        loadData()
    }

    fun setChartMode(mode: String) {
        _uiState.update { it.copy(chartMode = mode) }
    }

    fun loadMoreHistory() {
        _uiState.update { current ->
            val maxDays = current.dailyHistoryPoints.size
            val next = (current.historyVisibleCount + 10).coerceAtMost(maxDays)
            current.copy(historyVisibleCount = next)
        }
    }

    fun toggleExpandDate(date: String) {
        _uiState.update { current ->
            current.copy(expandedDate = if (current.expandedDate == date) null else date)
        }
    }

    private fun summariesForQuota(
        summaries: List<DailySummary>,
        currency: String,
        accountId: String?
    ): List<DailySummary> = summaries.filter { summary ->
        canonicalCurrency(summary.currency) == currency &&
            (accountId == null || summary.accountId == accountId)
    }

    /**
     * Builds percentage insights without passing quota rows through the money
     * accounting engines. The legacy three history columns carry monthly,
     * weekly and rolling-5h remaining percentages respectively.
     */
    private fun computeQuotaInsight(
        rawRecords: List<RawRecord>,
        summaries: List<DailySummary>,
        balances: List<com.balancesentinel.app.widget.AccountBalance>,
        accountId: String?,
        eligibleAccounts: List<AccountInfo>
    ): QuotaInsight? {
        val accountIds = if (accountId != null) {
            setOf(accountId)
        } else {
            eligibleAccounts.mapTo(linkedSetOf()) { it.id }
        }
        if (accountIds.isEmpty()) return null

        val scopedRaw = rawRecords.filter { it.accountId in accountIds }
        val scopedSummaries = summaries.filter { it.accountId in accountIds }
        val scopedBalances = balances.filter { it.accountId in accountIds }
        val periodIds = linkedSetOf<String>()
        scopedBalances.forEach { balance ->
            balance.quota?.periods?.forEach { periodIds += it.id }
            if (balance.currency == PERCENTAGE_CURRENCY &&
                balance.totalBalance.toDoubleOrNull()?.let { it in 0.0..100.0 } == true
            ) {
                periodIds += "monthly"
            }
        }
        if (scopedRaw.any { quotaRemaining(it, "monthly") != null }) periodIds += "monthly"
        if (scopedRaw.any { quotaRemaining(it, "weekly") != null }) periodIds += "weekly"
        if (scopedRaw.any { quotaRemaining(it, "rolling_5h") != null }) periodIds += "rolling_5h"
        if (scopedSummaries.any { quotaRemaining(it, "monthly") != null }) periodIds += "monthly"
        if (scopedSummaries.any { quotaRemaining(it, "weekly") != null }) periodIds += "weekly"
        if (scopedSummaries.any { quotaRemaining(it, "rolling_5h") != null }) periodIds += "rolling_5h"

        val periods = periodIds
            .sortedWith(compareBy({ quotaPeriodRank(it) }, { it }))
            .mapNotNull { periodId ->
                val liveCandidates = scopedBalances.mapNotNull { balance ->
                    val snapshot = balance.quota?.find(periodId)
                        ?: if (quotaPeriodRank(periodId) == 2) {
                            balance.totalBalance.toDoubleOrNull()
                                ?.takeIf { it in 0.0..100.0 }
                                ?.let { remaining ->
                                    QuotaPeriodSnapshot(
                                        id = periodId,
                                        usedPercent = 100.0 - remaining,
                                        remainingPercent = remaining
                                    )
                                }
                        } else {
                            null
                        }
                    snapshot?.let { Triple(balance, it, balance.lastUpdated) }
                }
                val observations = buildQuotaObservations(
                    rawRecords = scopedRaw,
                    summaries = scopedSummaries,
                    periodId = periodId
                )
                val history = if (accountId == null) {
                    mergeQuotaObservations(observations)
                } else {
                    collapseQuotaObservations(observations)
                }.let(::downsampleQuotaHistory)
                val live = liveCandidates.maxByOrNull { it.third }
                val latestObservationByAccount = observations
                    .groupBy { it.accountId }
                    .mapValues { (_, values) ->
                        values.maxWithOrNull(
                            compareBy<QuotaObservation> { it.timestamp }
                                .thenBy { it.accountId }
                        )
                    }
                val liveByAccount = liveCandidates.associateBy { it.first.accountId }
                val currentUsedValues = accountIds.mapNotNull { id ->
                    liveByAccount[id]?.second?.usedPercent?.toFloat()
                        ?: latestObservationByAccount[id]?.usedPercent
                }
                val used = currentUsedValues.takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
                    ?: return@mapNotNull null
                val remaining = (100f - used).coerceIn(0f, 100f)
                val latestBalance = live?.first
                val latestSnapshot = live?.second
                val latestObservation = observations.maxWithOrNull(
                    compareBy<QuotaObservation> { it.timestamp }
                        .thenBy { it.accountId }
                )
                val latestAccountId = latestBalance?.accountId ?: latestObservation?.accountId
                val latestAccountLabel = latestBalance?.label ?: latestAccountId?.let { id ->
                    eligibleAccounts.firstOrNull { it.id == id }?.label
                }
                QuotaInsightPeriod(
                    id = periodId,
                    usedPercent = used.coerceIn(0f, 100f),
                    remainingPercent = remaining.coerceIn(0f, 100f),
                    resetsAt = latestSnapshot?.resetsAt,
                    status = latestSnapshot?.status,
                    history = history,
                    latestRefreshAccountId = latestAccountId,
                    latestRefreshAccountLabel = latestAccountLabel,
                    latestRefreshAt = live?.third ?: latestObservation?.timestamp
                )
            }
        return QuotaInsight(periods).takeIf { it.periods.isNotEmpty() }
    }

    private data class QuotaObservation(
        val accountId: String,
        val timestamp: Long,
        val usedPercent: Float
    )

    private fun buildQuotaObservations(
        rawRecords: List<RawRecord>,
        summaries: List<DailySummary>,
        periodId: String
    ): List<QuotaObservation> {
        val observations = mutableListOf<QuotaObservation>()
        rawRecords.forEach { record ->
            quotaRemaining(record, periodId)?.let { remaining ->
                observations += QuotaObservation(
                    accountId = record.accountId,
                    timestamp = record.timestamp,
                    usedPercent = 100f - remaining
                )
            }
        }
        summaries.forEach { summary ->
            val date = runCatching { LocalDate.parse(summary.date) }.getOrNull() ?: return@forEach
            quotaRemaining(summary, periodId)?.let { remaining ->
                val timestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                observations += QuotaObservation(
                    accountId = summary.accountId,
                    timestamp = timestamp,
                    usedPercent = 100f - remaining
                )
            }
        }
        return observations.filter { it.usedPercent.isFinite() && it.usedPercent in 0f..100f }
    }

    /** Collapse duplicate samples for one account using their arithmetic mean. */
    private fun collapseQuotaObservations(
        observations: List<QuotaObservation>
    ): List<QuotaInsightPoint> = observations
        .groupBy { it.timestamp }
        .mapNotNull { (timestamp, values) ->
            values.map { it.usedPercent }.takeIf { it.isNotEmpty() }?.average()?.toFloat()?.let { used ->
                QuotaInsightPoint(timestamp, used)
            }
        }
        .sortedBy { it.timestamp }

    /**
     * Merge account timelines using carry-forward values. Percentages cannot be
     * summed, so each chart point is the arithmetic mean of all account values.
     * The timeline starts once every account
     * with history has an initial sample, preventing future values from being
     * backfilled into an earlier timestamp.
     */
    private fun mergeQuotaObservations(
        observations: List<QuotaObservation>
    ): List<QuotaInsightPoint> {
        val timelines = observations
            .groupBy { it.accountId }
            .mapValues { (_, accountObservations) ->
                collapseQuotaObservations(accountObservations)
            }
            .filterValues { it.isNotEmpty() }
        if (timelines.isEmpty()) return emptyList()
        if (timelines.size == 1) return timelines.values.single()

        val updatesByTimestamp = sortedMapOf<Long, MutableList<Pair<String, QuotaInsightPoint>>>()
        timelines.forEach { (accountId, timeline) ->
            timeline.forEach { point ->
                updatesByTimestamp.getOrPut(point.timestamp) { mutableListOf() }
                    .add(accountId to point)
            }
        }
        val firstCompleteTimestamp = timelines.values
            .mapNotNull { it.firstOrNull()?.timestamp }
            .maxOrNull()
            ?: return emptyList()
        val latestByAccount = mutableMapOf<String, QuotaInsightPoint>()
        val merged = mutableListOf<QuotaInsightPoint>()
        for ((timestamp, updates) in updatesByTimestamp) {
            updates.forEach { (accountId, point) -> latestByAccount[accountId] = point }
            if (timestamp < firstCompleteTimestamp || latestByAccount.size < timelines.size) continue
            val used = latestByAccount.values.map { it.usedPercent }.average().toFloat()
            merged += QuotaInsightPoint(timestamp, used)
        }
        return merged
    }

    /** Keep the chart readable when daily summaries and dense refresh samples overlap. */
    private fun downsampleQuotaHistory(
        points: List<QuotaInsightPoint>,
        maxSamples: Int = 240
    ): List<QuotaInsightPoint> {
        if (points.size <= maxSamples) return points
        val bucketSize = kotlin.math.ceil(points.size / maxSamples.toDouble()).toInt()
        return points.chunked(bucketSize).map { bucket ->
            QuotaInsightPoint(
                timestamp = bucket.last().timestamp,
                usedPercent = bucket.map { it.usedPercent }.average().toFloat()
            )
        }
    }

    private fun quotaRemaining(record: RawRecord, periodId: String): Float? = when (quotaPeriodRank(periodId)) {
        0 -> record.toppedUpBalance
        1 -> record.grantedBalance
        2 -> record.totalBalance
        else -> null
    }?.takeIf { it.isFinite() && it in 0f..100f && it != UNKNOWN_QUOTA_REMAINING.toFloat() }

    private fun quotaRemaining(summary: DailySummary, periodId: String): Float? = when (quotaPeriodRank(periodId)) {
        0 -> summary.toppedUpBalanceClose
        1 -> summary.grantedBalanceClose
        2 -> summary.close
        else -> null
    }?.takeIf { it.isFinite() && it in 0f..100f && it != UNKNOWN_QUOTA_REMAINING.toFloat() }

    private fun canonicalCurrency(value: String): String? {
        val canonical = value.trim().uppercase(Locale.ROOT)
        if (canonical == PERCENTAGE_CURRENCY) return canonical
        if (canonical.length != 3) return null
        return runCatching { Currency.getInstance(canonical).currencyCode }.getOrNull()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras
        ): T {
            check(modelClass.isAssignableFrom(InsightsViewModel::class.java))
            return InsightsViewModel(
                application = application,
                savedStateHandle = extras.createSavedStateHandle()
            ) as T
        }
    }
    companion object {
        private const val KEY_ACCOUNT_ID = "insights.selectedAccountId"
        private const val KEY_CURRENCY = "insights.selectedCurrency"
        private const val KEY_RANGE_DAYS = "insights.rangeDays"
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
            val updatesByTs = sortedMapOf<Long, MutableList<Pair<Int, IntradayPoint>>>()
            for ((idx, output) in outputs.withIndex()) {
                for (point in output.trendPoints) {
                    updatesByTs.getOrPut(point.timestamp) { mutableListOf() }
                        .add(idx to point)
                }
            }

            // Start once every account has an initial sample. This keeps the first
            // aggregate point complete without backfilling a future balance into
            // timestamps that predate that account's first observation.
            val firstCompleteTimestamp = outputs
                .mapNotNull { it.trendPoints.firstOrNull()?.timestamp }
                .maxOrNull()
                ?: return IntradayOutput(
                    emptyList(), IntradayBillReport(0f, 0f, 0f, 0f), 0
                )
            val lastPerAccount = mutableMapOf<Int, IntradayPoint>()
            val merged = mutableListOf<IntradayPoint>()

            for ((ts, updates) in updatesByTs) {
                // 更新有变化的账户
                for ((idx, point) in updates) {
                    lastPerAccount[idx] = point
                }
                if (ts < firstCompleteTimestamp) continue
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

            // Start once every account has an initial sample. A missing day means no
            // new sample, not a zero balance; carry the latest known close forward
            // only after the complete aggregate timeline has begun.
            val updatesByDate = sortedMapOf<String, MutableList<Pair<Int, DailyPoint>>>()
            for ((index, output) in outputs.withIndex()) {
                for (point in output.dailyPoints) {
                    updatesByDate.getOrPut(point.date) { mutableListOf() }.add(index to point)
                }
            }

            val firstCompleteDate = outputs
                .mapNotNull { it.dailyPoints.firstOrNull()?.date }
                .maxOrNull()
                ?: return DailyOutput(
                    emptyList(), DailyBillReport(0f, 0f, 0f, 0f, ""), null, "", true, true
                )
            val latestByAccount = mutableMapOf<Int, DailyPoint>()
            val merged = mutableListOf<DailyPoint>()
            for ((date, updates) in updatesByDate) {
                val carriedCloseByAccount = latestByAccount.mapValues { (_, point) -> point.balance }
                updates.forEach { (index, point) ->
                    latestByAccount[index] = point
                }
                if (date < firstCompleteDate) continue
                val updatesByAccount = updates.toMap()
                merged += DailyPoint(
                    date = date,
                    balance = latestByAccount.values.sumOf { it.balance.toDouble() }.toFloat(),
                    consumed = updates.sumOf { (_, point) -> point.consumed.toDouble() }.toFloat(),
                    toppedUp = updates.sumOf { (_, point) -> point.toppedUp.toDouble() }.toFloat(),
                    granted = updates.sumOf { (_, point) -> point.granted.toDouble() }.toFloat(),
                    isGapFill = updates.all { (_, point) -> point.isGapFill },
                    open = latestByAccount.keys.sumOf { index ->
                        val open = updatesByAccount[index]?.open
                            ?: carriedCloseByAccount[index]
                            ?: latestByAccount.getValue(index).open
                        open.toDouble()
                    }.toFloat(),
                    sampleCount = updates.maxOfOrNull { (_, point) -> point.sampleCount } ?: 0
                )
            }
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
         * Subscription balances are percentages and therefore average across
         * accounts, while real percentage-point usage remains additive.
         */
        fun mergeQuotaDailyOutputs(outputs: List<DailyOutput>): DailyOutput {
            val populatedOutputs = outputs.filter { it.dailyPoints.isNotEmpty() }
            if (populatedOutputs.isEmpty()) return DailyOutput(
                emptyList(), DailyBillReport(0f, 0f, 0f, 0f, ""), null, "", true, true
            )
            if (populatedOutputs.size == 1) return populatedOutputs.single().copy(estimate = null)

            val updatesByDate = sortedMapOf<String, MutableList<Pair<Int, DailyPoint>>>()
            populatedOutputs.forEachIndexed { index, output ->
                output.dailyPoints.forEach { point ->
                    updatesByDate.getOrPut(point.date) { mutableListOf() }.add(index to point)
                }
            }
            val firstCompleteDate = populatedOutputs
                .mapNotNull { it.dailyPoints.firstOrNull()?.date }
                .maxOrNull()
                ?: return DailyOutput(
                    emptyList(), DailyBillReport(0f, 0f, 0f, 0f, ""), null, "", true, true
                )
            val latestByAccount = mutableMapOf<Int, DailyPoint>()
            val merged = mutableListOf<DailyPoint>()
            updatesByDate.forEach { (date, updates) ->
                val carriedClose = latestByAccount.mapValues { (_, point) -> point.balance }
                updates.forEach { (index, point) -> latestByAccount[index] = point }
                if (date < firstCompleteDate || latestByAccount.size < populatedOutputs.size) return@forEach
                val updatesByAccount = updates.toMap()
                merged += DailyPoint(
                    date = date,
                    balance = latestByAccount.values.map { it.balance }.average().toFloat(),
                    consumed = updates.sumOf { (_, point) -> point.consumed.toDouble() }.toFloat(),
                    toppedUp = updates.sumOf { (_, point) -> point.toppedUp.toDouble() }.toFloat(),
                    granted = updates.sumOf { (_, point) -> point.granted.toDouble() }.toFloat(),
                    isGapFill = updates.all { (_, point) -> point.isGapFill },
                    open = latestByAccount.keys.map { index ->
                        updatesByAccount[index]?.open
                            ?: carriedClose[index]
                            ?: latestByAccount.getValue(index).open
                    }.average().toFloat(),
                    sampleCount = updates.sumOf { (_, point) -> point.sampleCount }
                )
            }
            val periodLabel = populatedOutputs.firstOrNull()?.periodLabel.orEmpty()
            val totalMonthly = merged.sumOf { it.consumed.toDouble() }.toFloat()
            val totalRolling = merged.sumOf { it.toppedUp.toDouble() }.toFloat()
            val totalWeekly = merged.sumOf { it.granted.toDouble() }.toFloat()
            return DailyOutput(
                dailyPoints = merged,
                billReport = DailyBillReport(
                    consumed = totalMonthly,
                    toppedUp = totalRolling,
                    granted = totalWeekly,
                    netChange = -(totalMonthly + totalRolling + totalWeekly),
                    periodLabel = periodLabel
                ),
                estimate = null,
                periodLabel = periodLabel,
                isEmpty = merged.isEmpty(),
                insufficientData = merged.none { it.consumed > 0f || it.toppedUp > 0f || it.granted > 0f }
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
            val method: EstimateMethod
            val methodDays: Int

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
                        method = EstimateMethod.MULTI_ACCOUNT_LINEAR_REGRESSION
                    } else {
                        dailyRate = meanRate
                        method = EstimateMethod.MULTI_ACCOUNT_AVERAGE
                    }
                } else {
                    dailyRate = meanRate
                    method = EstimateMethod.MULTI_ACCOUNT_AVERAGE
                }
                methodDays = rangeDays
            } else {
                dailyRate = meanRate
                method = EstimateMethod.MULTI_ACCOUNT_SIMPLE_COUNT
                methodDays = withConsumption.size
            }

            val daysRemaining = lastBalance / dailyRate

            val (depletionMonth, depletionDay) = try {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, daysRemaining.roundToInt())
                (cal.get(Calendar.MONTH) + 1) to cal.get(Calendar.DAY_OF_MONTH)
            } catch (_: Exception) {
                0 to 0
            }

            return DepletionEstimate(
                dailyRate = dailyRate,
                daysRemaining = daysRemaining,
                depletionMonth = depletionMonth,
                depletionDay = depletionDay,
                method = method,
                methodDays = methodDays
            )
        }
    }
}
