package com.balancesentinel.app.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.theme.WalletColors
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.balancesentinel.app.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNavigateToSettings: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(uiState.statusSummary?.serviceStarting) {
        if (uiState.statusSummary?.serviceStarting == true) {
            while (true) {
                kotlinx.coroutines.delay(1500L)
                viewModel.loadStatusSummary()
            }
        }
    }

    // 添加账户对话框
    var showAddDialog by remember { mutableStateOf(false) }
    if (showAddDialog) {
        AddAccountDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { label, key ->
                viewModel.addAccount(label, key)
                showAddDialog = false
            }
        )
    }

    // 删除确认对话框
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    deleteTarget?.let { (id, label) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.home_delete_account_title)) },
            text = { Text(stringResource(R.string.home_delete_account_confirm, label)) },
            confirmButton = {
                TextButton(onClick = { viewModel.removeAccount(id); deleteTarget = null }) {
                    Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.home_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshBalance() },
                        enabled = !uiState.isLoading && uiState.accounts.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_refresh),
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings),
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_account),
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        val pullRefreshState = rememberPullRefreshState(
            refreshing = uiState.isLoading,
            onRefresh = { viewModel.refreshBalance() }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pullRefresh(pullRefreshState)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 错误消息
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.errorMessage?.let { msg ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(CustomIcons.ErrorOutline, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // 空状态
                if (uiState.accounts.isEmpty()) {
                    EmptyAccountsHint()
                } else {
                    // 每账户一张余额卡片
                    uiState.accounts.forEach { account ->
                        val balance = uiState.accountBalances[account.id]
                        AccountBalanceCard(
                            accountLabel = account.label,
                            accountId = account.id,
                            balance = balance,
                            isLoading = uiState.isLoading,
                            lastRefreshTime = uiState.lastRefreshTime,
                            now = now,
                            onLongPress = { deleteTarget = Pair(account.id, account.label) }
                        )
                    }
                }

                // 状态栏
                if (uiState.accounts.isNotEmpty()) {
                    SimpleStatusBar(uiState)
                }
            }

            PullRefreshIndicator(
                refreshing = uiState.isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 空状态提示
// ═══════════════════════════════════════════════════════════

@Composable
private fun EmptyAccountsHint() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 添加账户对话框
// ═══════════════════════════════════════════════════════════

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.add_account_label)) },
                    placeholder = { Text(stringResource(R.string.add_account_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.add_account_key_label)) },
                    placeholder = { Text(stringResource(R.string.add_account_key_hint)) },
                    visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    leadingIcon = {
                        Box(modifier = Modifier
                            .clickable {
                                val clipText = clipboardManager.getText()?.text ?: ""
                                if (clipText.isNotBlank()) apiKey = clipText.trim()
                            }
                            .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.add_account_paste_key),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) CustomIcons.VisibilityOff else CustomIcons.Visibility,
                                contentDescription = if (showKey) stringResource(R.string.add_account_hide_key)
                                    else stringResource(R.string.add_account_show_key)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(label, apiKey) },
                enabled = label.isNotBlank() && apiKey.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.home_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        }
    )
}

// ═══════════════════════════════════════════════════════════
// 账户余额卡片
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountBalanceCard(
    accountLabel: String,
    accountId: String,
    balance: BalanceResponse?,
    isLoading: Boolean,
    lastRefreshTime: Long,
    now: Long,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current

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
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(accountLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (isLoading && balance == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                        Text(formatRefreshTime(lastRefreshTime, now, context),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 币种余额
                if (balance.balanceInfos.isEmpty()) {
                    Text(stringResource(R.string.home_no_data), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    balance.balanceInfos
                        .sortedByDescending { it.totalBalance.toDoubleOrNull() ?: 0.0 }
                        .forEach { info ->
                        BalanceInfoCard(info)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            } else if (isLoading) {
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.home_query_failed), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
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
            Icon(
                imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (available) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (available) stringResource(R.string.home_status_available)
                    else stringResource(R.string.home_status_insufficient),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (available) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun BalanceInfoCard(info: BalanceInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.balance_total, FormatUtils.currencySymbol(info.currency)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(FormatUtils.formatAmount(info.totalBalance),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.balance_granted, FormatUtils.formatAmount(info.grantedBalance)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.balance_topped_up, FormatUtils.formatAmount(info.toppedUpBalance)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 状态栏
// ═══════════════════════════════════════════════════════════

@Composable
private fun SimpleStatusBar(uiState: com.balancesentinel.app.ui.viewmodel.HomeUiState) {
    val summary = uiState.statusSummary ?: return

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (svcColor, svcText) = when {
                    summary.serviceStarting -> Pair(WalletColors.warning, stringResource(R.string.home_service_starting))
                    summary.serviceAlive    -> Pair(WalletColors.success, stringResource(R.string.settings_service_running))
                    else                    -> Pair(MaterialTheme.colorScheme.error, stringResource(R.string.settings_service_stopped))
                }
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = svcColor
                ) {}
                Spacer(modifier = Modifier.width(6.dp))
                Text(svcText, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val batColor = if (!summary.batteryOptimizing) WalletColors.success else WalletColors.warning
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = batColor
                ) {}
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (!summary.batteryOptimizing) stringResource(R.string.settings_battery_ok)
                    else stringResource(R.string.settings_battery_restricted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

private fun formatRefreshTime(timestamp: Long, now: Long, context: Context): String {
    if (timestamp <= 0) return ""
    val diff = now - timestamp
    return when {
        diff < 60_000 -> context.getString(R.string.home_just_now)
        diff < 3_600_000 -> context.getString(R.string.home_minutes_ago, (diff / 60_000).toInt())
        diff < 86_400_000 -> context.getString(R.string.home_hours_ago, (diff / 3_600_000).toInt())
        else -> {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            fmt.format(java.util.Date(timestamp))
        }
    }
}
