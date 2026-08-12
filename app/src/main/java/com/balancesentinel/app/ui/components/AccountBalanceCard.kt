package com.balancesentinel.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.ui.icons.ProviderIcons
import com.balancesentinel.app.ui.theme.WalletColors
import com.balancesentinel.app.ui.viewmodel.AccountRefreshUiState
import com.balancesentinel.app.util.LocalizedFormatter

/**
 * 账户余额卡片（支持多供应商）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountBalanceCard(
    accountLabel: String,
    accountId: String,
    providerType: ProviderType,
    balance: BalanceResponse?,
    now: Long,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    accountMutationsEnabled: Boolean = true,
    accountRefreshEnabled: Boolean = true,
    onRefresh: () -> Unit = {},
    refreshState: AccountRefreshUiState = AccountRefreshUiState()
) {
    val context = LocalContext.current
    val providerName = stringResource(providerType.displayNameResource())
    val cardDescription = stringResource(R.string.account_card_long_press_delete, accountLabel)
    val formatter = remember(context, context.resources.configuration.locales[0]) {
        LocalizedFormatter(context)
    }
    val effectiveLoading = refreshState.isLoading
    val effectiveDataTimestamp = refreshState.dataTimestamp ?: refreshState.lastSuccessAt ?: 0L
    var showMenu by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }

    // 调试对话框
    if (showDebugDialog) {
        DebugDialog(
            accountId = accountId,
            accountLabel = accountLabel,
            onDismiss = { showDebugDialog = false }
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = cardDescription
            }
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    if (accountMutationsEnabled) onLongPress()
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 顶部：供应商图标 + 账户名 + 菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：供应商图标 + 账户信息
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 供应商图标（带背景）
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(ProviderIcons.getColor(providerType)).copy(alpha = 0.1f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = ProviderIcons.getIcon(providerType),
                            contentDescription = providerName,
                            tint = Color(ProviderIcons.getColor(providerType)),
                            modifier = Modifier
                                .padding(12.dp)
                                .size(24.dp)
                        )
                    }

                    // 账户名称和供应商
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            accountLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            providerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 右侧：加载指示器 + 状态 + 菜单
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 菜单按钮
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.account_more_actions),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.account_refresh)) },
                                enabled = accountRefreshEnabled,
                                onClick = {
                                    showMenu = false
                                    onRefresh()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.account_edit)) },
                                enabled = accountMutationsEnabled,
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.account_debug)) },
                                onClick = {
                                    showMenu = false
                                    showDebugDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error) },
                                enabled = accountMutationsEnabled,
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 余额内容区域
            when {
                effectiveLoading -> {
                    // 加载状态
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                stringResource(R.string.loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                balance != null -> {
                    // 有余额数据
                    // 状态芯片 + 刷新时间
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusChip(
                            available = balance.isAvailable,
                            hasBalance = balance.balanceInfos.isNotEmpty()
                        )
                        if (effectiveDataTimestamp > 0) {
                            Text(
                                formatter.formatRelativeTime(effectiveDataTimestamp, now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (refreshState.errorMessage != null) {
                        Text(
                            refreshState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (refreshState.stale) {
                        Text(
                            stringResource(R.string.home_query_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 币种余额列表
                    if (balance.balanceInfos.isEmpty()) {
                        Text(
                            stringResource(R.string.home_no_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        balance.balanceInfos
                            .sortedByDescending { it.totalBalance.toDoubleOrNull() ?: 0.0 }
                            .forEach { info ->
                                BalanceInfoCard(info, formatter)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                    }
                }
                refreshState.errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            refreshState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                refreshState.stale && effectiveDataTimestamp > 0 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.home_query_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                effectiveDataTimestamp > 0 -> {
                    // 曾经查询过但失败了
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                stringResource(R.string.home_query_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                formatter.formatRelativeTime(effectiveDataTimestamp, now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    // 从未查询过
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.account_waiting_refresh),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
// ═══════════════════════════════════════════════════════════
// 状态芯片
// ═══════════════════════════════════════════════════════════

@Composable
private fun StatusChip(available: Boolean, hasBalance: Boolean = true) {
    val (backgroundColor, textColor, text) = when {
        !hasBalance -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.account_status_no_data)
        )
        available -> Triple(
            WalletColors.success.copy(alpha = 0.15f),
            WalletColors.success,
            stringResource(R.string.home_status_available)
        )
        else -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.error,
            stringResource(R.string.home_status_insufficient)
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 状态指示点
            Surface(
                modifier = Modifier.size(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = textColor
            ) {}
            // 状态文本
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 余额信息卡片
// ═══════════════════════════════════════════════════════════

@Composable
private fun BalanceInfoCard(
    info: com.balancesentinel.app.data.model.BalanceInfo,
    formatter: LocalizedFormatter
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 币种和总额
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 币种标签
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Text(
                        info.currency,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // 总额
                Text(
                    formatter.formatAmount(info.totalBalance),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 赠送和充值明细
            if (info.grantedBalance != null || info.toppedUpBalance != null) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (info.grantedBalance != null) {
                        DetailItem(
                            label = stringResource(R.string.balance_granted_label),
                            value = formatter.formatAmount(info.grantedBalance),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (info.toppedUpBalance != null) {
                        DetailItem(
                            label = stringResource(R.string.balance_topped_up_label),
                            value = formatter.formatAmount(info.toppedUpBalance),
                            modifier = Modifier.weight(1f),
                            alignment = Alignment.End
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 明细项
// ═══════════════════════════════════════════════════════════

@Composable
private fun DetailItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start
) {
    Column(
        modifier = modifier,
        horizontalAlignment = alignment
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
