package com.balancesentinel.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.data.engine.DailyBillReport
import com.balancesentinel.app.data.engine.DailyPoint
import com.balancesentinel.app.ui.theme.WalletColors
import com.balancesentinel.app.data.engine.DepletionEstimate
import com.balancesentinel.app.data.engine.IntradayBillReport
import com.balancesentinel.app.data.engine.IntradayPoint
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.ui.viewmodel.InsightsViewModel
import com.balancesentinel.app.util.FormatUtils

/**
 * 洞察页 v2 — 双引擎双卡片架构。
 *
 * Card 1: 24h 视图 — IntradayLineChart + 账单报表
 * Card 2: 长期视图 — DailyLineChart + FilterChip(7/14/30/90/365) + 账单报表 + 消耗预估
 */
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (uiState.availableCurrencies.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.insights_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 币种 Tab（仅多币种时显示）
            if (uiState.availableCurrencies.size > 1) {
                CurrencyTabRow(
                    currencies = uiState.availableCurrencies,
                    selected = uiState.selectedCurrency,
                    onSelect = { viewModel.selectCurrency(it) }
                )
            }

            // 账户筛选
            if (uiState.accounts.isNotEmpty()) {
                AccountFilterRow(
                    accounts = uiState.accounts,
                    selectedAccountId = uiState.selectedAccountId,
                    onSelect = { viewModel.selectAccount(it) }
                )
            }

            // ── Card 1: 24h 视图 ──
            IntradayCard(
                points = uiState.intradayOutput?.trendPoints ?: emptyList(),
                bill = uiState.intradayOutput?.billReport
                    ?: IntradayBillReport(0f, 0f, 0f, 0f),
                currency = uiState.selectedCurrency
            )

            // ── Card 2: 长期视图 ──
            DailyCard(
                points = uiState.dailyOutput?.dailyPoints ?: emptyList(),
                bill = uiState.dailyOutput?.billReport
                    ?: DailyBillReport(0f, 0f, 0f, 0f, ""),
                estimate = uiState.dailyOutput?.estimate,
                currency = uiState.selectedCurrency,
                rangeDays = uiState.rangeDays,
                insufficientData = uiState.dailyOutput?.insufficientData ?: true,
                chartMode = uiState.chartMode,
                onChartModeChange = { viewModel.setChartMode(it) },
                onRangeDaysChange = { viewModel.setRangeDays(it) }
            )

            // ── Card 3: 历史日汇总 ──
            DailyHistoryCard(
                points = uiState.dailyOutput?.dailyPoints ?: emptyList(),
                currency = uiState.selectedCurrency,
                visibleCount = uiState.historyVisibleCount,
                expandedDate = uiState.expandedDate,
                onToggleExpand = { viewModel.toggleExpandDate(it) },
                onLoadMore = { viewModel.loadMoreHistory() }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 币种 Tab 行
// ═══════════════════════════════════════════════════════════

@Composable
private fun CurrencyTabRow(
    currencies: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val selectedIndex = currencies.indexOf(selected).coerceAtLeast(0)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        androidx.compose.material3.TabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier.fillMaxWidth()
        ) {
            currencies.forEachIndexed { index, currency ->
                androidx.compose.material3.Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelect(currency) },
                    text = { Text(currency) }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 账户筛选
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountFilterRow(
    accounts: List<AccountInfo>,
    selectedAccountId: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        FilterChip(
            selected = selectedAccountId == null,
            onClick = { onSelect(null) },
            label = {
                Text(
                    stringResource(R.string.insights_all_accounts),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            },
            modifier = Modifier.height(28.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        accounts.forEach { account ->
            FilterChip(
                selected = selectedAccountId == account.id,
                onClick = { onSelect(account.id) },
                label = {
                    Text(
                        account.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                },
                modifier = Modifier.height(28.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Card 1: 24h 视图
// ═══════════════════════════════════════════════════════════

@Composable
private fun IntradayCard(
    points: List<IntradayPoint>,
    bill: IntradayBillReport,
    currency: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.insights_intraday_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.insights_data_insufficient),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IntradayLineChart(
                    data = points,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )

                // 图例
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.insights_label_chart_actual),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // 绿色 ▲ 充值
                    Text(
                        text = "▲",
                        style = MaterialTheme.typography.labelSmall,
                        color = WalletColors.success
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.insights_label_topped_up),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // 紫色 ◆ 赠送
                    Text(
                        text = "◆",
                        style = MaterialTheme.typography.labelSmall,
                        color = WalletColors.granted
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.insights_label_granted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 账单报表（消耗/充值/赠送/净变化）
            if (bill.consumed > 0f || bill.toppedUp > 0f || bill.granted > 0f) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                if (bill.consumed > 0f) {
                    LabeledLine(
                        label = stringResource(R.string.insights_label_consumed),
                        value = "-${FormatUtils.currencySymbol(currency)}%.2f".format(bill.consumed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (bill.toppedUp > 0f) {
                    LabeledLine(
                        label = stringResource(R.string.insights_label_topped_up),
                        value = "+${FormatUtils.currencySymbol(currency)}%.2f".format(bill.toppedUp),
                        color = WalletColors.success
                    )
                }
                if (bill.granted > 0f) {
                    LabeledLine(
                        label = stringResource(R.string.insights_label_granted),
                        value = "+${FormatUtils.currencySymbol(currency)}%.2f".format(bill.granted),
                        color = WalletColors.granted
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(6.dp))

                val netColor = when {
                    bill.netChange > 0 -> WalletColors.success
                    bill.netChange < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val prefix = if (bill.netChange >= 0) "+" else ""
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.insights_label_net),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$prefix${FormatUtils.currencySymbol(currency)}%.2f".format(bill.netChange),
                        style = MaterialTheme.typography.labelMedium,
                        color = netColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Card 2: 长期视图
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyCard(
    points: List<DailyPoint>,
    bill: DailyBillReport,
    estimate: DepletionEstimate?,
    currency: String,
    rangeDays: Int,
    insufficientData: Boolean = false,
    chartMode: String = "balance",
    onChartModeChange: (String) -> Unit = {},
    onRangeDaysChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── 标题行 + 时间范围切换 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.insights_daily_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        7 to R.string.insights_trend_7d,
                        14 to R.string.insights_trend_14d,
                        30 to R.string.insights_trend_30d,
                        90 to R.string.insights_trend_90d,
                        365 to R.string.insights_trend_1y
                    ).forEach { (days, resId) ->
                        FilterChip(
                            selected = rangeDays == days,
                            onClick = { onRangeDaysChange(days) },
                            label = {
                                Text(
                                    stringResource(resId),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.height(28.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 余额/消耗 图表模式切换（下划线样式，与时间 FilterChip 区分）
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                listOf(
                    "balance" to R.string.insights_chart_balance,
                    "consumed" to R.string.insights_chart_consumption
                ).forEach { (mode, resId) ->
                    val selected = chartMode == mode
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onChartModeChange(mode) }
                    ) {
                        Text(
                            stringResource(resId),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(2.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.insights_data_insufficient),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                DailyLineChart(
                    data = points,
                    chartMode = chartMode,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )

                // 图例
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.insights_label_chart_actual),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "▲",
                        style = MaterialTheme.typography.labelSmall,
                        color = WalletColors.success
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.insights_label_topped_up),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Show gap-fill legend only if there are gap-fill points
                    if (points.any { it.isGapFill }) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "●",
                            style = MaterialTheme.typography.labelSmall,
                            color = WalletColors.neutralGrey
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "无数据日",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 账单报表
            if (bill.consumed > 0f || bill.toppedUp > 0f || bill.granted > 0f) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                if (bill.consumed > 0f) {
                    LabeledLine(
                        label = stringResource(R.string.insights_label_consumed),
                        value = "-${FormatUtils.currencySymbol(currency)}%.2f".format(bill.consumed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (bill.toppedUp > 0f) {
                    LabeledLine(
                        label = stringResource(R.string.insights_label_topped_up),
                        value = "+${FormatUtils.currencySymbol(currency)}%.2f".format(bill.toppedUp),
                        color = WalletColors.success
                    )
                }
                if (bill.granted > 0f) {
                    LabeledLine(
                        label = stringResource(R.string.insights_label_granted),
                        value = "+${FormatUtils.currencySymbol(currency)}%.2f".format(bill.granted),
                        color = WalletColors.granted
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(6.dp))

                val netColor = when {
                    bill.netChange > 0 -> WalletColors.success
                    bill.netChange < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val prefix = if (bill.netChange >= 0) "+" else ""
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.insights_label_net),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$prefix${FormatUtils.currencySymbol(currency)}%.2f".format(bill.netChange),
                        style = MaterialTheme.typography.labelMedium,
                        color = netColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 消耗预估
            if (estimate != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        EstimateMetric(
                            label = stringResource(R.string.insights_current_balance),
                            value = "${FormatUtils.currencySymbol(currency)}%.2f".format(
                                points.lastOrNull()?.balance ?: 0f
                            ),
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )
                        EstimateMetric(
                            label = stringResource(R.string.insights_daily_consumption),
                            value = "${FormatUtils.currencySymbol(currency)}%.2f".format(estimate.dailyRate),
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )
                        val estColor = when {
                            estimate.daysRemaining < 3 -> MaterialTheme.colorScheme.error
                            estimate.daysRemaining < 7 -> WalletColors.warning
                            else -> WalletColors.success
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.insights_estimated_days),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.insights_days_remaining)
                                    .format(estimate.daysRemaining),
                                style = MaterialTheme.typography.titleMedium,
                                color = estColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = estimate.depletionDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = estColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = estimate.methodLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else if (points.isNotEmpty() && !insufficientData) {
                // 数据充足但无消耗预估 — 余额稳定或增长（斜率 ≤ 0）
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.insights_trend_stable),
                        style = MaterialTheme.typography.titleSmall,
                        color = WalletColors.success,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (points.isNotEmpty() && insufficientData) {
                // 数据不足 (< 3 个有消耗的天) — 无法预估
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.insights_trend_insufficient),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EstimateMetric(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 24h 折线图（IntradayLineChart）
// X 轴: HH:mm 智能抽稀，充值点 ▲ 绿色，赠送点 ◆ 紫色
// ═══════════════════════════════════════════════════════════

@Composable
private fun IntradayLineChart(
    data: List<IntradayPoint>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val textColor = android.graphics.Color.argb(0x99, 0x6B, 0x6E, 0x8A)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val topUpMarkerColor = WalletColors.success
    val grantMarkerColor = WalletColors.granted
    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val values = data.map { it.actualBalance }
        val minVal = values.min()
        val maxVal = values.max()
        val rawRange = maxVal - minVal

        val range: Float
        val adjustedMin: Float
        if (rawRange <= 0.0001f) {
            val halfSpan = maxOf(minVal * 0.1f, 0.5f, minVal * 0.01f + 0.1f)
            range = halfSpan * 2f
            adjustedMin = minVal - halfSpan
        } else {
            val headroom = rawRange * 0.12f
            range = rawRange + headroom * 2f
            adjustedMin = minVal - headroom
        }

        val leftPadding = 84f
        val bottomPadding = 38f
        val topPadding = 38f
        val rightPadding = 84f

        val chartWidth = size.width - leftPadding - rightPadding
        val chartHeight = size.height - topPadding - bottomPadding

        // ── Y 轴网格线 ──
        val gridLines = 2
        for (i in 0..gridLines) {
            val y = topPadding + chartHeight * (1f - i.toFloat() / gridLines)
            val value = adjustedMin + range * (i.toFloat() / gridLines)

            drawLine(
                color = gridColor,
                start = Offset(leftPadding, y),
                end = Offset(size.width - rightPadding, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatChartValue(value),
                leftPadding - 8f, y + 4f,
                android.graphics.Paint().apply {
                    color = textColor
                    textSize = 36f
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }
            )
        }

        // ── 数据点坐标 ──
        val points = data.indices.map { i ->
            val x = leftPadding + (chartWidth * i.toFloat() / (data.size - 1).coerceAtLeast(1))
            val y = topPadding + chartHeight * (1f - (values[i] - adjustedMin) / range)
            Offset(x, y)
        }

        if (points.size >= 2) {
            // ── 填充渐变 ──
            val fillPath = Path().apply {
                moveTo(points[0].x, topPadding + chartHeight)
                lineTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
                lineTo(points.last().x, topPadding + chartHeight)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.25f),
                        lineColor.copy(alpha = 0.02f)
                    ),
                    startY = points.minOf { it.y },
                    endY = topPadding + chartHeight
                )
            )

            // ── 折线 ──
            val linePath = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // ── 最高点/最低点/当前金额横虚线 ──
        // 标签在右侧；数值接近时上下错位避免重叠，仅真正重合时跳过
        if (rawRange > 0.0001f && data.size >= 2) {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            val highlightLineColor = lineColor.copy(alpha = 0.35f)
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(0xCC, 0x6B, 0x6E, 0x8A)
                textSize = 34f
                textAlign = android.graphics.Paint.Align.LEFT
                isAntiAlias = true
            }

            val maxIdx = values.indices.maxByOrNull { values[it] }!!
            val minIdx = values.indices.minByOrNull { values[it] }!!
            val maxY = points[maxIdx].y
            val minY = points[minIdx].y
            val curIdx = data.lastIndex
            val curY = points[curIdx].y
            val curIsMax = kotlin.math.abs(curY - maxY) < 3f
            val curIsMin = kotlin.math.abs(curY - minY) < 3f
            val minIsMax = kotlin.math.abs(minY - maxY) < 3f
            val nearCurMax = kotlin.math.abs(curY - maxY) < 36f
            val nearCurMin = kotlin.math.abs(curY - minY) < 36f
            val nearMinMax = kotlin.math.abs(minY - maxY) < 36f
            val labelX = size.width - rightPadding + 8f

            // 最高点横虚线 + 标签（右侧上方）
            drawLine(
                color = highlightLineColor,
                start = Offset(leftPadding, maxY),
                end = Offset(size.width - rightPadding, maxY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashEffect
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatChartValue(values[maxIdx]),
                labelX, maxY - 6f,
                labelPaint
            )

            // 当前金额横虚线 + 标签（右侧；仅与最高点重合时跳过）
            if (!curIsMax) {
                drawLine(
                    color = highlightLineColor,
                    start = Offset(leftPadding, curY),
                    end = Offset(size.width - rightPadding, curY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                // 靠近最低点时放线上方，让最低点标签可放下方
                val curLabelY = if (nearCurMin && !nearCurMax) curY - 6f else curY + 42f
                drawContext.canvas.nativeCanvas.drawText(
                    formatChartValue(values[curIdx]),
                    labelX, curLabelY,
                    labelPaint
                )
            }

            // 最低点横虚线 + 标签（右侧；仅与最高点或当前金额重合时跳过）
            if (!minIsMax && !curIsMin) {
                drawLine(
                    color = highlightLineColor,
                    start = Offset(leftPadding, minY),
                    end = Offset(size.width - rightPadding, minY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                // 靠近当前金额或最高点时放下方
                val minLabelY = if (nearCurMin || nearMinMax) minY + 42f else minY - 6f
                drawContext.canvas.nativeCanvas.drawText(
                    formatChartValue(values[minIdx]),
                    labelX, minLabelY,
                    labelPaint
                )
            }
        }

        // ── 充值点 ▲ 标记 ──
        for (i in data.indices) {
            if (data[i].isTopUp) {
                val p = points[i]
                val markerSize = 8.dp.toPx()
                val triPath = Path().apply {
                    moveTo(p.x, p.y - markerSize - 6.dp.toPx())
                    lineTo(p.x - markerSize, p.y - 4.dp.toPx())
                    lineTo(p.x + markerSize, p.y - 4.dp.toPx())
                    close()
                }
                drawPath(path = triPath, color = topUpMarkerColor)
            }
        }

        // ── 赠送点 ◆ 标记 ──
        for (i in data.indices) {
            if (data[i].isGrant) {
                val p = points[i]
                val m = 5.dp.toPx()
                val diamondPath = Path().apply {
                    moveTo(p.x, p.y - m - 6.dp.toPx())
                    lineTo(p.x + m, p.y - 4.dp.toPx())
                    lineTo(p.x, p.y - 4.dp.toPx() + m * 2)
                    lineTo(p.x - m, p.y - 4.dp.toPx())
                    close()
                }
                drawPath(path = diamondPath, color = grantMarkerColor)
            }
        }

        // ── 数据点圆点（≤20 个点时绘制） ──
        val showDots = data.size <= 20
        if (showDots) {
            points.forEach { point ->
                drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = point)
                drawCircle(color = lineColor, radius = 2.5.dp.toPx(), center = point)
            }
        }

        // ── X 轴标签: HH:mm，智能抽稀 ──
        val maxLabels = if (data.size <= 8) data.size else if (data.size <= 24) 8 else 6
        if (data.size <= maxLabels) {
            for (i in data.indices) {
                val label = timeFormat.format(java.util.Date(data[i].timestamp))
                val x = points[i].x
                drawContext.canvas.nativeCanvas.drawText(
                    label, x, size.height - 2f,
                    android.graphics.Paint().apply {
                        color = textColor
                        textSize = 32f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                )
            }
        } else {
            val step = data.size.toFloat() / maxLabels
            var nextLabelAt = 0f
            for (i in data.indices) {
                if (i.toFloat() >= nextLabelAt || i == data.lastIndex) {
                    val label = timeFormat.format(java.util.Date(data[i].timestamp))
                    val x = points[i].x
                    drawContext.canvas.nativeCanvas.drawText(
                        label, x, size.height - 2f,
                        android.graphics.Paint().apply {
                            color = textColor
                            textSize = 32f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                    )
                    nextLabelAt += step
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 长期折线图（DailyLineChart）
// X 轴: 均匀 MM-DD，补零日虚点+虚线，充值日 ▲ 绿色
// ═══════════════════════════════════════════════════════════

@Composable
private fun DailyLineChart(
    data: List<DailyPoint>,
    chartMode: String = "balance",
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val textColor = android.graphics.Color.argb(0x99, 0x6B, 0x6E, 0x8A)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val topUpMarkerColor = WalletColors.success
    val grantMarkerColor = WalletColors.granted
    val gapColor = WalletColors.neutralGrey

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val values = if (chartMode == "consumed") {
            data.map { it.consumed }
        } else {
            data.map { it.balance }
        }
        val minVal = values.min()
        val maxVal = values.max()
        val rawRange = maxVal - minVal

        val range: Float
        val adjustedMin: Float
        if (rawRange <= 0.0001f) {
            val halfSpan = maxOf(minVal * 0.1f, 0.5f, minVal * 0.01f + 0.1f)
            range = halfSpan * 2f
            adjustedMin = minVal - halfSpan
        } else {
            val headroom = rawRange * 0.12f
            range = rawRange + headroom * 2f
            adjustedMin = minVal - headroom
        }

        val leftPadding = 84f
        val bottomPadding = 38f
        val topPadding = 38f
        val rightPadding = 84f

        val chartWidth = size.width - leftPadding - rightPadding
        val chartHeight = size.height - topPadding - bottomPadding

        // ── Y 轴网格线 ──
        val gridLines = 2
        for (i in 0..gridLines) {
            val y = topPadding + chartHeight * (1f - i.toFloat() / gridLines)
            val value = adjustedMin + range * (i.toFloat() / gridLines)

            drawLine(
                color = gridColor,
                start = Offset(leftPadding, y),
                end = Offset(size.width - rightPadding, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatChartValue(value),
                leftPadding - 8f, y + 4f,
                android.graphics.Paint().apply {
                    color = textColor
                    textSize = 36f
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }
            )
        }

        // ── 数据点坐标 ──
        val points = data.indices.map { i ->
            val x = leftPadding + (chartWidth * i.toFloat() / (data.size - 1).coerceAtLeast(1))
            val y = topPadding + chartHeight * (1f - (values[i] - adjustedMin) / range)
            Offset(x, y)
        }

        if (points.size >= 2) {
            // ── 填充渐变 ──
            val fillPath = Path().apply {
                moveTo(points[0].x, topPadding + chartHeight)
                lineTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
                lineTo(points.last().x, topPadding + chartHeight)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.25f),
                        lineColor.copy(alpha = 0.02f)
                    ),
                    startY = points.minOf { it.y },
                    endY = topPadding + chartHeight
                )
            )

            // ── 分段绘制折线：补零段为虚线 ──
            for (i in 0 until points.size - 1) {
                val isGapSegment = data[i].isGapFill || data[i + 1].isGapFill
                val segPath = Path().apply {
                    moveTo(points[i].x, points[i].y)
                    lineTo(points[i + 1].x, points[i + 1].y)
                }
                drawPath(
                    path = segPath,
                    color = if (isGapSegment) gapColor else lineColor,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (isGapSegment)
                            PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        else null
                    )
                )
            }
        }

        // ── 最高点/最低点/当前金额横虚线 ──
        // 标签在右侧；数值接近时上下错位避免重叠，仅真正重合时跳过
        if (rawRange > 0.0001f && data.size >= 2) {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            val highlightLineColor = lineColor.copy(alpha = 0.35f)
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(0xCC, 0x6B, 0x6E, 0x8A)
                textSize = 34f
                textAlign = android.graphics.Paint.Align.LEFT
                isAntiAlias = true
            }

            val maxIdx = values.indices.maxByOrNull { values[it] }!!
            val minIdx = values.indices.minByOrNull { values[it] }!!
            val maxY = points[maxIdx].y
            val minY = points[minIdx].y
            val curIdx = data.lastIndex
            val curY = points[curIdx].y
            val curIsMax = kotlin.math.abs(curY - maxY) < 3f
            val curIsMin = kotlin.math.abs(curY - minY) < 3f
            val minIsMax = kotlin.math.abs(minY - maxY) < 3f
            val nearCurMax = kotlin.math.abs(curY - maxY) < 36f
            val nearCurMin = kotlin.math.abs(curY - minY) < 36f
            val nearMinMax = kotlin.math.abs(minY - maxY) < 36f
            val labelX = size.width - rightPadding + 8f

            // 最高点横虚线 + 标签（右侧上方）
            drawLine(
                color = highlightLineColor,
                start = Offset(leftPadding, maxY),
                end = Offset(size.width - rightPadding, maxY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashEffect
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatChartValue(values[maxIdx]),
                labelX, maxY - 6f,
                labelPaint
            )

            // 当前金额横虚线 + 标签（右侧；仅与最高点重合时跳过）
            if (!curIsMax) {
                drawLine(
                    color = highlightLineColor,
                    start = Offset(leftPadding, curY),
                    end = Offset(size.width - rightPadding, curY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                // 靠近最低点时放线上方，让最低点标签可放下方
                val curLabelY = if (nearCurMin && !nearCurMax) curY - 6f else curY + 42f
                drawContext.canvas.nativeCanvas.drawText(
                    formatChartValue(values[curIdx]),
                    labelX, curLabelY,
                    labelPaint
                )
            }

            // 最低点横虚线 + 标签（右侧；仅与最高点或当前金额重合时跳过）
            if (!minIsMax && !curIsMin) {
                drawLine(
                    color = highlightLineColor,
                    start = Offset(leftPadding, minY),
                    end = Offset(size.width - rightPadding, minY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                // 靠近当前金额或最高点时放下方
                val minLabelY = if (nearCurMin || nearMinMax) minY + 42f else minY - 6f
                drawContext.canvas.nativeCanvas.drawText(
                    formatChartValue(values[minIdx]),
                    labelX, minLabelY,
                    labelPaint
                )
            }
        }

        // ── 充值日 ▲ 标记（仅余额模式）──
        if (chartMode == "balance") {
            for (i in data.indices) {
                if (data[i].toppedUp > 0f) {
                    val p = points[i]
                    val markerSize = 8.dp.toPx()
                    val triPath = Path().apply {
                        moveTo(p.x, p.y - markerSize - 6.dp.toPx())
                        lineTo(p.x - markerSize, p.y - 4.dp.toPx())
                        lineTo(p.x + markerSize, p.y - 4.dp.toPx())
                        close()
                    }
                    drawPath(path = triPath, color = topUpMarkerColor)
                }
            }

            // ── 赠送日 ◆ 标记（仅余额模式）──
            for (i in data.indices) {
                if (data[i].granted > 0f) {
                    val p = points[i]
                    val m = 5.dp.toPx()
                    val diamondPath = Path().apply {
                        moveTo(p.x, p.y - m - 6.dp.toPx())
                        lineTo(p.x + m, p.y - 4.dp.toPx())
                        lineTo(p.x, p.y - 4.dp.toPx() + m * 2)
                        lineTo(p.x - m, p.y - 4.dp.toPx())
                        close()
                    }
                    drawPath(path = diamondPath, color = grantMarkerColor)
                }
            }
        }

        // ── 补零日灰点 ──
        for (i in data.indices) {
            if (data[i].isGapFill) {
                drawCircle(
                    color = gapColor,
                    radius = 4.dp.toPx(),
                    center = points[i]
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = points[i]
                )
            }
        }

        // ── 非补零日数据点（≤30 个点时绘制） ──
        if (data.size <= 30) {
            data.indices.filter { !data[it].isGapFill }.forEach { i ->
                drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = points[i])
                drawCircle(color = lineColor, radius = 2.5.dp.toPx(), center = points[i])
            }
        }

        // ── X 轴标签: MM-DD 均匀分布 ──
        val maxLabels = if (data.size <= 7) data.size else if (data.size <= 30) 7 else if (data.size <= 90) 10 else 12
        if (data.size <= maxLabels) {
            for (i in data.indices) {
                val label = data[i].date.takeLast(5)
                val x = points[i].x
                drawContext.canvas.nativeCanvas.drawText(
                    label, x, size.height - 2f,
                    android.graphics.Paint().apply {
                        color = textColor
                        textSize = 32f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                )
            }
        } else {
            val step = (data.size - 1).toFloat() / (maxLabels - 1)
            for (j in 0 until maxLabels) {
                val i = (j * step).toInt().coerceAtMost(data.lastIndex)
                val label = data[i].date.takeLast(5)
                val x = points[i].x
                drawContext.canvas.nativeCanvas.drawText(
                    label, x, size.height - 2f,
                    android.graphics.Paint().apply {
                        color = textColor
                        textSize = 32f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Card 3: 历史日汇总
// ═══════════════════════════════════════════════════════════

@Composable
private fun DailyHistoryCard(
    points: List<DailyPoint>,
    currency: String,
    visibleCount: Int,
    expandedDate: String?,
    onToggleExpand: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    if (points.isEmpty()) return

    val reversed = points.reversed()
    val visible = reversed.take(visibleCount)
    val hasMore = visibleCount < reversed.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.insights_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            visible.forEach { point ->
                val isExpanded = expandedDate == point.date
                val isGap = point.consumed == 0f && point.toppedUp == 0f && point.granted == 0f

                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isGap) Modifier.clickable { onToggleExpand(point.date) }
                                else Modifier
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = point.date,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isGap)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (point.consumed > 0f) {
                                Text(
                                    text = "-${FormatUtils.currencySymbol(currency)}%.2f".format(point.consumed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            } else if (isGap) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                text = "${FormatUtils.currencySymbol(currency)}%.2f".format(point.balance),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isGap) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isExpanded) "▼" else "▶",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isExpanded) {
                        val netChange = point.toppedUp + point.granted - point.consumed
                        val netColor = when {
                            netChange > 0 -> WalletColors.success
                            netChange < 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val netPrefix = if (netChange >= 0) "+" else ""

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            HistoryDetailRow(stringResource(R.string.insights_label_consumed),
                                "-${FormatUtils.currencySymbol(currency)}%.2f".format(point.consumed),
                                MaterialTheme.colorScheme.error)
                            if (point.toppedUp > 0f) {
                                HistoryDetailRow(stringResource(R.string.insights_label_topped_up),
                                    "+${FormatUtils.currencySymbol(currency)}%.2f".format(point.toppedUp),
                                    WalletColors.success)
                            }
                            if (point.granted > 0f) {
                                HistoryDetailRow(stringResource(R.string.insights_label_granted),
                                    "+${FormatUtils.currencySymbol(currency)}%.2f".format(point.granted),
                                    WalletColors.granted)
                            }
                            HistoryDetailRow(stringResource(R.string.insights_label_net),
                                "$netPrefix${FormatUtils.currencySymbol(currency)}%.2f".format(netChange),
                                netColor)
                            HistoryDetailRow(stringResource(R.string.insights_history_open),
                                "${FormatUtils.currencySymbol(currency)}%.2f".format(point.open),
                                MaterialTheme.colorScheme.onSurfaceVariant)
                            HistoryDetailRow(stringResource(R.string.insights_history_close),
                                "${FormatUtils.currencySymbol(currency)}%.2f".format(point.balance),
                                MaterialTheme.colorScheme.onSurfaceVariant)
                            HistoryDetailRow(stringResource(R.string.insights_history_samples),
                                "${point.sampleCount}",
                                MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (point != visible.last()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                }
            }

            if (hasMore) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onLoadMore) {
                        Text(
                            text = stringResource(R.string.insights_history_load_more)
                                .format(10),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (points.size > 7) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.insights_history_all_loaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 共享辅助
// ═══════════════════════════════════════════════════════════

@Composable
private fun LabeledLine(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatChartValue(value: Float): String {
    return when {
        value >= 10000 -> "%.1f万".format(value / 10000f)
        value >= 1000 -> "%.1fk".format(value / 1000f)
        value >= 10 -> "%.0f".format(value)
        value >= 1 -> "%.1f".format(value)
        value >= 0.01f -> "%.2f".format(value)
        else -> "%.4f".format(value)
    }
}

