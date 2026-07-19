package com.balancesentinel.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.ui.icons.ProviderIcons
import com.balancesentinel.app.ui.theme.WalletColors
import com.balancesentinel.app.util.FormatUtils

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
    isLoading: Boolean,
    lastRefreshTime: Long,
    now: Long,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                contentDescription = accountLabel + "，长按可删除"
            }
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 标题行（带供应商图标和菜单）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：供应商图标 + 账户名
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = ProviderIcons.getIcon(providerType),
                        contentDescription = providerType.displayName,
                        tint = Color(ProviderIcons.getColor(providerType)),
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            accountLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            providerType.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 右侧：加载指示器 + 菜单
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isLoading && balance == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    // 更多菜单
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (balance != null) {
                // 状态 + 刷新时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(available = balance.isAvailable)
                    if (lastRefreshTime > 0) {
                        Text(
                            formatRefreshTime(lastRefreshTime, now, context),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 币种余额
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
                            BalanceInfoCard(info)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                }
            } else if (isLoading) {
                Text(
                    stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.home_query_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusChip(available: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (available) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .padding(1.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (available) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                ) {}
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (available) "可用"
                else "不可用",
                style = MaterialTheme.typography.labelSmall,
                color = if (available) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun BalanceInfoCard(info: com.balancesentinel.app.data.model.BalanceInfo) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    info.currency,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    FormatUtils.formatAmount(info.totalBalance),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (info.grantedBalance != null || info.toppedUpBalance != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (info.grantedBalance != null) {
                        Text(
                            "赠送: ${FormatUtils.formatAmount(info.grantedBalance)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (info.toppedUpBalance != null) {
                        Text(
                            "充值: ${FormatUtils.formatAmount(info.toppedUpBalance)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatRefreshTime(lastRefreshTime: Long, now: Long, context: android.content.Context): String {
    val diff = now - lastRefreshTime
    return when {
        diff < 60_000 -> "刚刚刷新"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        else -> "${diff / 86400_000}天前"
    }
}
