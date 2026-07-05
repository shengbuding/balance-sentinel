package com.balancesentinel.app.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.DataManagementUiState
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.PendingAction

/**
 * 内部子页面路由。
 */
private enum class DataSubPage { HUB, CLEAR_DATA, BACKUP_RESTORE }

/**
 * 数据管理 — Hub 页面。
 * 概览 + 导航到子页面（清除数据 / 备份迁移）+ 重置操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit
) {
    var subPage by remember { mutableStateOf(DataSubPage.HUB) }

    when (subPage) {
        DataSubPage.CLEAR_DATA -> ClearDataScreen(
            viewModel = viewModel,
            onBack = { subPage = DataSubPage.HUB }
        )
        DataSubPage.BACKUP_RESTORE -> BackupRestoreScreen(
            viewModel = viewModel,
            onBack = { subPage = DataSubPage.HUB }
        )
        DataSubPage.HUB -> DataHub(
            viewModel = viewModel,
            onBack = onBack,
            onNavigateToClear = { subPage = DataSubPage.CLEAR_DATA },
            onNavigateToBackup = { subPage = DataSubPage.BACKUP_RESTORE }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataHub(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit,
    onNavigateToClear: () -> Unit,
    onNavigateToBackup: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    // Toast 消息
    LaunchedEffect(uiState.resultMessage) {
        val msg = uiState.resultMessage
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearResultMessage()
        }
    }

    // 确认对话框（重置类操作）
    uiState.pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissAction() },
            title = { Text(stringResource(R.string.data_confirm_title)) },
            text = {
                Text(
                    when (action) {
                        PendingAction.ResetAlarmCounters -> stringResource(R.string.data_confirm_reset_alarm)
                        PendingAction.ResetSettings -> stringResource(R.string.data_confirm_reset_settings)
                        PendingAction.ResetEntireApp -> stringResource(R.string.data_confirm_reset_app)
                        else -> ""
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.executeAction(action) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (action == PendingAction.ResetEntireApp)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.data_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissAction() }) {
                    Text(stringResource(R.string.data_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.data_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Section 1: 存储概览 ──
            StorageStatsCard(uiState)

            // ── Section 2: 导航入口 ──
            SectionHeader(stringResource(R.string.data_section_actions))

            NavCard(
                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_nav_clear_title),
                description = stringResource(R.string.data_nav_clear_desc),
                onClick = onNavigateToClear
            )

            NavCard(
                icon = { Icon(CustomIcons.SaveAlt, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_nav_backup_title),
                description = stringResource(R.string.data_nav_backup_desc),
                onClick = onNavigateToBackup
            )

            // ── Section 3: 重置 ──
            SectionHeader(stringResource(R.string.data_section_reset))

            ActionCard(
                icon = { Icon(Icons.Filled.Refresh, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_reset_alarm_title),
                description = stringResource(R.string.data_reset_alarm_desc),
                buttonText = stringResource(R.string.data_reset_alarm_btn),
                onAction = { viewModel.requestAction(PendingAction.ResetAlarmCounters) }
            )

            ActionCard(
                icon = { Icon(Icons.Filled.Settings, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_reset_settings_title),
                description = stringResource(R.string.data_reset_settings_desc),
                buttonText = stringResource(R.string.data_reset_settings_btn),
                onAction = { viewModel.requestAction(PendingAction.ResetSettings) }
            )

            // ── Section 4: 危险区域 ──
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
            )

            DestructiveActionCard(
                icon = { Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_reset_app_title),
                description = stringResource(R.string.data_reset_app_desc),
                buttonText = stringResource(R.string.data_reset_app_btn),
                onAction = { viewModel.requestAction(PendingAction.ResetEntireApp) }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 存储统计 Card（从原页面保留）
// ═══════════════════════════════════════════════════════════

@Composable
private fun StorageStatsCard(state: DataManagementUiState) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.data_storage_stats_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            val hasAnyData = state.rawRecordCount > 0 || state.dailySummaryCount > 0 ||
                    state.usageSnapshotCount > 0 || state.refreshLogCount > 0

            if (!hasAnyData) {
                Text(
                    text = stringResource(R.string.data_stat_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (state.rawRecordCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_raw_records),
                        value = stringResource(R.string.data_stat_raw_records_value, state.rawRecordCount, state.rawRecordDistinctDates)
                    )
                }
                if (state.dailySummaryCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_daily_summaries),
                        value = "${state.dailySummaryCount}"
                    )
                }
                if (state.usageSnapshotCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_usage_snapshots),
                        value = "${state.usageSnapshotCount}"
                    )
                }
                if (state.refreshLogCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_refresh_logs),
                        value = "${state.refreshLogCount}"
                    )
                }
                if (state.widgetErrorCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_widget_errors),
                        value = "${state.widgetErrorCount}"
                    )
                }
                if (state.widgetBalanceCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_widget_balances),
                        value = "${state.widgetBalanceCount}"
                    )
                }
                if (state.crashCount > 0) {
                    StorageStatRow(
                        label = stringResource(R.string.data_stat_crash_logs),
                        value = "${state.crashCount}"
                    )
                }

                // 闹钟计数器始终显示
                Text(
                    text = stringResource(R.string.data_stat_alarm_counters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AlarmCounterChip(stringResource(R.string.data_stat_alarm_set), state.alarmCounters.totalSet)
                    AlarmCounterChip(stringResource(R.string.data_stat_alarm_fired), state.alarmCounters.totalFired)
                    AlarmCounterChip(stringResource(R.string.data_stat_alarm_cancel), state.alarmCounters.totalCancelled)
                    AlarmCounterChip(stringResource(R.string.data_stat_alarm_drop), state.alarmCounters.totalDropped)
                }
            }
        }
    }
}

@Composable
private fun AlarmCounterChip(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (value > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StorageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 导航卡片（点击进入子页面）
// ═══════════════════════════════════════════════════════════

@Composable
private fun NavCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Section 标题 + ActionCard（保留在 Hub 的重置项）
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ActionCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun DestructiveActionCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(buttonText)
            }
        }
    }
}
