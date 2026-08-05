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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.components.AccountBalanceCard
import com.balancesentinel.app.ui.components.AddAccountDialog
import com.balancesentinel.app.ui.components.EditAccountDialog
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
            onAdd = { draft ->
                viewModel.addAccount(draft)
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
            text = {
                Column {
                    Text(stringResource(R.string.home_delete_account_confirm, label))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "同时删除刷新日志、用量记录、原始数据、缓存与告警状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAccount(id)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }

    // 编辑账户对话框
    var editTarget by remember { mutableStateOf<AccountInfo?>(null) }
    editTarget?.let { account ->
        EditAccountDialog(
            account = account,
            onDismiss = { editTarget = null },
            onConfirm = { draft ->
                viewModel.editAccount(account.id, draft)
                editTarget = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.accounts.isNotEmpty()) {
                            Text(
                                "${uiState.accounts.size} 个账户",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                actions = {
                    // 刷新按钮
                    IconButton(
                        onClick = { viewModel.refreshBalance() },
                        enabled = !uiState.isLoading && uiState.accounts.isNotEmpty()
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.home_refresh),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    // 设置按钮
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.testTag("add_account_fab"),
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_account)) },
                shape = RoundedCornerShape(16.dp)
            )
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 错误消息
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.errorMessage?.let { msg ->
                        ErrorMessageCard(
                            message = msg,
                            onRetry = { viewModel.refreshBalance() }
                        )
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
                            providerType = account.providerType,
                            balance = balance,
                            isLoading = uiState.isLoading,
                            lastRefreshTime = uiState.lastRefreshTime,
                            now = now,
                            onLongPress = { deleteTarget = Pair(account.id, account.label) },
                            onEdit = { editTarget = account },
                            onDelete = { deleteTarget = Pair(account.id, account.label) },
                            onRefresh = { viewModel.refreshSingleAccount(account.id) }
                        )
                    }

                    // 状态栏
                    SimpleStatusBar(uiState)
                }

                // 底部间距（避免FAB遮挡）
                Spacer(modifier = Modifier.height(80.dp))
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
// 错误消息卡片
// ═══════════════════════════════════════════════════════════

@Composable
private fun ErrorMessageCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                CustomIcons.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "重试",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 空状态提示
// ═══════════════════════════════════════════════════════════

@Composable
private fun EmptyAccountsHint() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标容器
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(96.dp)
            ) {
                Icon(
                    Icons.Filled.Home,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(24.dp)
                        .size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 引导按钮
            FilledTonalButton(
                onClick = { /* 由FAB处理 */ },
                enabled = false,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.home_add_account))
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 服务状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val (svcColor, svcText) = when {
                    summary.serviceStarting -> Pair(WalletColors.warning, stringResource(R.string.home_service_starting))
                    summary.serviceAlive    -> Pair(WalletColors.success, stringResource(R.string.settings_service_running))
                    else                    -> Pair(MaterialTheme.colorScheme.error, stringResource(R.string.settings_service_stopped))
                }
                // 状态指示器（带动画效果）
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = svcColor
                ) {}
                Text(
                    svcText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            // 电池状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val batColor = if (!summary.batteryOptimizing) WalletColors.success else WalletColors.warning
                val batText = if (!summary.batteryOptimizing) {
                    stringResource(R.string.settings_battery_ok)
                } else {
                    stringResource(R.string.settings_battery_restricted)
                }
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = batColor
                ) {}
                Text(
                    batText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
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
