package com.balancesentinel.app.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.ConfigManager
import com.balancesentinel.app.data.repository.DataExporter
import com.balancesentinel.app.data.repository.LogExporter
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.DataManagementUiState
import com.balancesentinel.app.ui.viewmodel.PendingAction
import com.balancesentinel.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    // ── Toast 消息 ──
    LaunchedEffect(uiState.resultMessage) {
        val msg = uiState.resultMessage
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearResultMessage()
        }
    }

    // ── 确认对话框 ──
    uiState.pendingAction?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = { viewModel.executeAction(action) },
            onDismiss = { viewModel.dismissAction() }
        )
    }

    // ── 数据导出 launcher ──
    val exportDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            if (DataExporter.hasData(context)) {
                val ok = DataExporter.exportToUri(context, uri)
                if (ok) {
                    Toast.makeText(context, context.getString(R.string.data_export_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.data_export_fail), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, context.getString(R.string.data_no_data), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 配置导出 launcher ──
    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val prefs = WidgetPrefs(context)
            val keyMgr = ApiKeyManager(context)
            val ok = ConfigManager.exportToUri(context, uri, keyMgr, prefs)
            if (ok) {
                Toast.makeText(context, context.getString(R.string.data_config_export_success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.data_config_export_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 配置导入 launcher ──
    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val config = ConfigManager.importFromUri(context, uri)
            if (config != null) {
                ConfigManager.applyConfigDirectly(context, config)
                Toast.makeText(context, context.getString(R.string.data_config_import_success, config.accounts.size), Toast.LENGTH_SHORT).show()
                viewModel.loadStats()
            } else {
                Toast.makeText(context, context.getString(R.string.data_config_import_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 调试报告导出 ──
    val exportDebugReport = {
        try {
            val path = LogExporter.export(context)
            if (path != null) {
                Toast.makeText(context, context.getString(R.string.data_debug_report_success, java.io.File(path).name), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, context.getString(R.string.data_export_fail), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.data_export_exception, e.message), Toast.LENGTH_SHORT).show()
        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ═══════════════════════════════════════════
            // Section 1: 存储统计
            // ═══════════════════════════════════════════
            StorageStatsCard(uiState)

            // ═══════════════════════════════════════════
            // Section 2: 清除数据
            // ═══════════════════════════════════════════
            SectionHeader(stringResource(R.string.data_section_clear))

            DestructiveActionCard(
                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_clear_raw_records_title),
                description = stringResource(R.string.data_clear_raw_records_desc),
                buttonText = stringResource(R.string.data_clear_raw_records_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearRawRecords) }
            )

            DestructiveActionCard(
                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_clear_summaries_title),
                description = stringResource(R.string.data_clear_summaries_desc),
                buttonText = stringResource(R.string.data_clear_summaries_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearDailySummaries) }
            )

            DestructiveActionCard(
                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_clear_usage_title),
                description = stringResource(R.string.data_clear_usage_desc),
                buttonText = stringResource(R.string.data_clear_usage_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearUsageSnapshots) }
            )

            DestructiveActionCard(
                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_clear_logs_title),
                description = stringResource(R.string.data_clear_logs_desc),
                buttonText = stringResource(R.string.data_clear_logs_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearRefreshLogs) }
            )

            DestructiveActionCard(
                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.data_clear_widget_errors_title),
                description = stringResource(R.string.data_clear_widget_errors_desc),
                buttonText = stringResource(R.string.data_clear_widget_errors_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearWidgetErrors) }
            )

            // ═══════════════════════════════════════════
            // Section 3: 重置
            // ═══════════════════════════════════════════
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

            // ═══════════════════════════════════════════
            // Section 4: 导出/导入
            // ═══════════════════════════════════════════
            SectionHeader(stringResource(R.string.data_section_export_import))

            ActionCard(
                icon = { Icon(CustomIcons.BarChart, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_export_history_title),
                description = stringResource(R.string.data_export_history_desc),
                buttonText = stringResource(R.string.data_export_btn),
                onAction = { exportDataLauncher.launch("wallet_sentinel_data.json") }
            )

            ActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_export_config_title),
                description = stringResource(R.string.data_export_config_desc),
                buttonText = stringResource(R.string.data_export_config_btn),
                onAction = { exportConfigLauncher.launch("wallet_sentinel_config.json") }
            )

            ActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_import_config_title),
                description = stringResource(R.string.data_import_config_desc),
                buttonText = stringResource(R.string.data_import_config_btn),
                onAction = { importConfigLauncher.launch(arrayOf("application/json", "*/*")) }
            )

            ActionCard(
                icon = { Icon(CustomIcons.SaveAlt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_debug_report_title),
                description = stringResource(R.string.data_debug_report_desc),
                buttonText = stringResource(R.string.data_debug_report_btn),
                onAction = exportDebugReport
            )

            // ═══════════════════════════════════════════
            // Section 5: 重置整个应用（危险区域）
            // ═══════════════════════════════════════════
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
// 存储统计 Card
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
private fun StorageStatRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
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
// Section 标题
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

// ═══════════════════════════════════════════════════════════
// 确认对话框
// ═══════════════════════════════════════════════════════════

@Composable
private fun ConfirmDialog(
    action: PendingAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = when (action) {
        PendingAction.ClearRawRecords -> stringResource(R.string.data_confirm_clear_records)
        PendingAction.ClearDailySummaries -> stringResource(R.string.data_confirm_clear_summaries)
        PendingAction.ClearUsageSnapshots -> stringResource(R.string.data_confirm_clear_usage)
        PendingAction.ClearRefreshLogs -> stringResource(R.string.data_confirm_clear_logs)
        PendingAction.ClearWidgetErrors -> stringResource(R.string.data_confirm_clear_widget_errors)
        PendingAction.ResetAlarmCounters -> stringResource(R.string.data_confirm_reset_alarm)
        PendingAction.ResetSettings -> stringResource(R.string.data_confirm_reset_settings)
        PendingAction.ResetEntireApp -> stringResource(R.string.data_confirm_reset_app)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (action == PendingAction.ResetEntireApp)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.data_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.data_cancel))
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════
// 共享 ActionCard（普通 + 危险操作）
// ═══════════════════════════════════════════════════════════

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
