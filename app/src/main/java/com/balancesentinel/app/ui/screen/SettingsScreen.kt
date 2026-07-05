package com.balancesentinel.app.ui.screen

import android.content.Context

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import com.balancesentinel.app.ui.theme.WalletColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.data.repository.RefreshStats


import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import com.balancesentinel.app.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: HomeViewModel, onBack: () -> Unit, onNavigateToLog: () -> Unit, onNavigateToDataManagement: () -> Unit, onNavigateToAlertSettings: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.loadStatusSummary()
        viewModel.loadRefreshStats()
    }

    // 拦截系统返回键/手势，回到首页而非退出应用
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val refreshStats by viewModel.refreshStats.collectAsStateWithLifecycle()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 刷新设置 ──
            WidgetSettingsSection(viewModel, uiState.refreshIntervalSeconds)

            // ── 预警设置入口 ──
            AlertSettingsEntryRow(onClick = onNavigateToAlertSettings)

            // ── 数据管理入口 ──
            DataManagementEntryRow(onClick = onNavigateToDataManagement)

            // ── 隐私政策 ──
            PrivacyPolicyRow()

            // ── 系统状态面板 ──
            StatusSummaryPanel(uiState.statusSummary)

            // ── 刷新统计仪表盘 ──
            RefreshStatsCard(refreshStats)

            // ── 刷新日志入口 ──
            LogEntryRow(onClick = onNavigateToLog)

            // ── 电池优化提示 ──
            // 仅在电池优化开启且服务不健康时显示；关闭电池优化或服务恢复后自动消失
            if (uiState.statusSummary?.batteryOptimizing == true && uiState.statusSummary?.serviceAlive != true) {
                BatteryOptimizationHint()
            }

            // ── 崩溃日志 ──
            if (uiState.crashLogs.isNotEmpty()) {
                CrashLogCard(
                    crashes = uiState.crashLogs,
                    onClear = { viewModel.clearCrashes() }
                )
            }

            // ── 版本信息 ──
            VersionInfo()
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 系统状态面板
// ═══════════════════════════════════════════════════════════

@Composable
private fun StatusSummaryPanel(summary: com.balancesentinel.app.data.repository.StatusSummary?) {
    if (summary == null) return

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.settings_system_status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusChip(
                    label = stringResource(R.string.settings_front_service),
                    ok = summary.serviceAlive,
                    okText = stringResource(R.string.settings_surviving),
                    failText = stringResource(R.string.settings_stopped)
                )
                StatusChip(
                    label = stringResource(R.string.settings_battery_opt),
                    ok = !summary.batteryOptimizing,
                    okText = stringResource(R.string.settings_disabled),
                    failText = stringResource(R.string.settings_limited)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (summary.alarmMethod.isNotEmpty())
                        stringResource(R.string.settings_alarm_format, FormatUtils.methodLabel(LocalContext.current, summary.alarmMethod))
                    else
                        stringResource(R.string.settings_alarm_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val total = summary.totalSet
            if (total > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.alarm_stats_format,
                            total, summary.totalFired, summary.totalCancelled, summary.totalDropped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (summary.totalDropped > 0) {
                        val arrivalPct = if (total > 0) summary.totalFired * 100 / total else 0
                        Text(
                            text = stringResource(R.string.alarm_arrival_rate, "$arrivalPct", summary.totalDropped),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (summary.totalDropped > summary.totalFired)
                                MaterialTheme.colorScheme.error else WalletColors.warning
                        )
                    } else if (summary.totalCancelled > 0 && total > 0) {
                        val arrivalPct = summary.totalFired * 100 / total
                        Text(
                            text = stringResource(R.string.alarm_arrival_rate_fired, "$arrivalPct", summary.totalFired, total),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (arrivalPct < 50) WalletColors.warning
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (summary.expectedNextRefresh > 0) {
                val delay = summary.alarmDelaySeconds
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_expected_schedule, FormatUtils.formatFullTime(summary.expectedNextRefresh)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (delay > 0)
                            stringResource(R.string.settings_delayed_format, (delay / 60).toInt(), (delay % 60).toInt())
                        else
                            stringResource(R.string.settings_waiting),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (delay > 120) FontWeight.Bold else FontWeight.Normal,
                        color = if (delay > 120) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (summary.lastHeartbeat > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_last_heartbeat, ((System.currentTimeMillis() - summary.lastHeartbeat) / 1000).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 刷新统计仪表盘
// ═══════════════════════════════════════════════════════════

@Composable
private fun RefreshStatsCard(stats: RefreshStats?) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.settings_refresh_stats),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (stats == null || stats.totalAttempts == 0) {
                Text(
                    stringResource(R.string.refresh_stats_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val rateText = if (stats.successRate >= 0) "${stats.successRate}%" else "--"
                Text(
                    stringResource(R.string.refresh_stats_success_rate, rateText),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        stats.successRate < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        stats.successRate >= 80 -> WalletColors.success
                        stats.successRate >= 50 -> WalletColors.warning
                        else -> MaterialTheme.colorScheme.error
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    stringResource(
                        R.string.refresh_stats_format,
                        stats.successes, stats.failures, stats.skipped, stats.totalAttempts
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (stats.consecutiveFailures > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.refresh_stats_consecutive_fail, stats.consecutiveFailures),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (stats.lastSuccessTime > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val diff = System.currentTimeMillis() - stats.lastSuccessTime
                    val timeText = when {
                        diff < 60_000 -> stringResource(R.string.time_just_now)
                        diff < 3_600_000 -> stringResource(R.string.time_minutes_ago, (diff / 60_000).toInt())
                        diff < 86_400_000 -> stringResource(R.string.time_hours_ago, (diff / 3_600_000).toInt())
                        else -> {
                            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            sdf.format(java.util.Date(stats.lastSuccessTime))
                        }
                    }
                    Text(
                        stringResource(R.string.refresh_stats_last_success, timeText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean, okText: String, failText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$label: ", style = MaterialTheme.typography.bodySmall)
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (ok) WalletColors.successBg
            else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        ) {
            Text(
                text = if (ok) okText else failText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (ok) WalletColors.success else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════
// 刷新日志入口
// ═══════════════════════════════════════════════════════════

@Composable
private fun LogEntryRow(onClick: () -> Unit) {
    val logEntryLabel = stringResource(R.string.settings_log_entry)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = logEntryLabel
                }
                .clickable { onClick() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CustomIcons.History, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.settings_log_entry),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 电池优化提示
// ═══════════════════════════════════════════════════════════

@Composable
private fun BatteryOptimizationHint() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = WalletColors.warningBg)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null,
                    tint = WalletColors.warning, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_autorefresh_limited_title),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = WalletColors.warningText)
                    Text(stringResource(R.string.settings_autorefresh_limited_desc),
                        style = MaterialTheme.typography.bodySmall, color = WalletColors.warningTextDim.copy(alpha = 0.8f))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = {
                val pkg = "package:${context.packageName}"
                // 三级 fallback：直接请求 → 电池优化设置列表 → 应用详情页
                val intents = listOf(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .apply { data = android.net.Uri.parse(pkg) },
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .apply { data = android.net.Uri.parse(pkg) }
                )
                for (intent in intents) {
                    try {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        break
                    } catch (_: Exception) {
                        continue
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = WalletColors.warning),
                shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_close_battery_opt), fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 崩溃日志
// ═══════════════════════════════════════════════════════════

@Composable
private fun CrashLogCard(
    crashes: List<com.balancesentinel.app.CrashLogger.CrashEntry>,
    onClear: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Card(shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_crash_log, crashes.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)
                Row {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(
                            crashes.joinToString("\n---\n") { it.fullStack }))
                    }) {
                        Icon(CustomIcons.ContentCopy, stringResource(R.string.settings_copy), Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.settings_clear), Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            crashes.forEach { crash ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(crash.header,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 3)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 版本信息
// ═══════════════════════════════════════════════════════════

@Composable
private fun VersionInfo() {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) { "1.0.0" }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.settings_about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_about_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 小组件设置
// ═══════════════════════════════════════════════════════════

@Composable
private fun WidgetSettingsSection(viewModel: HomeViewModel, currentIntervalSec: Int) {
    var expanded by remember { mutableStateOf(false) }
    var inputValue by remember(currentIntervalSec) { mutableStateOf((currentIntervalSec / 60).toString()) }
    var isMinutes by remember { mutableStateOf(true) }

    fun applyInterval() {
        val num = inputValue.toIntOrNull()
        if (num != null && num > 0) {
            val seconds = if (isMinutes) num * 60 else num
            viewModel.setRefreshInterval(seconds)
        }
    }

    val refreshLabel = stringResource(R.string.settings_auto_refresh)
    val stateDesc = if (expanded) "$refreshLabel，已展开" else "$refreshLabel，已折叠"
    val minutesLabel = stringResource(R.string.settings_minutes)
    val secondsLabel = stringResource(R.string.settings_seconds)

    val displayLabel = if (isMinutes) {
        val min = currentIntervalSec / 60
        val sec = currentIntervalSec % 60
        if (sec == 0) stringResource(R.string.interval_minutes, min)
        else stringResource(R.string.interval_min_sec, min, sec)
    } else {
        stringResource(R.string.interval_seconds, currentIntervalSec)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        stateDescription = stateDesc
                    }
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_auto_refresh), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "折叠" else "展开",
                    modifier = Modifier.size(20.dp))
            }
            Text(stringResource(R.string.settings_refresh_interval_label, displayLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.settings_value)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .semantics {
                                role = Role.Switch
                                stateDescription = if (isMinutes) minutesLabel else secondsLabel
                            }
                            .clickable { isMinutes = !isMinutes }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isMinutes) stringResource(R.string.settings_minutes) else stringResource(R.string.settings_seconds),
                            style = MaterialTheme.typography.bodyMedium)
                        Icon(CustomIcons.SwapHoriz, contentDescription = stringResource(R.string.settings_toggle_unit),
                            modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { applyInterval() }, shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.settings_confirm))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.settings_current_label, displayLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 预警设置入口
// ═══════════════════════════════════════════════════════════

@Composable
private fun AlertSettingsEntryRow(onClick: () -> Unit) {
    val alertLabel = stringResource(R.string.settings_alert_entry)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = alertLabel
                }
                .clickable { onClick() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.settings_alert_entry),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.settings_alert_entry_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 隐私政策入口
// ═══════════════════════════════════════════════════════════

@Composable
private fun PrivacyPolicyRow() {
    var showDialog by remember { mutableStateOf(false) }
    val privacyLabel = stringResource(R.string.settings_privacy_policy)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = privacyLabel
                }
                .clickable { showDialog = true }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.settings_privacy_policy),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_privacy_policy_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_privacy_policy)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(PRIVACY_POLICY_TEXT, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.settings_close))
                }
            }
        )
    }
}

private val PRIVACY_POLICY_TEXT = """
    钱包哨兵 — 隐私政策

    最后更新日期：2026 年 7 月 5 日

    概述
    钱包哨兵是一款 DeepSeek API 余额监控工具。我们高度重视您的隐私和数据安全。

    我们收集的信息
    • DeepSeek API Key：仅存储在本设备的加密存储中，用于查询余额
    • 不收集：个人身份信息、设备标识符、位置信息、联系人等

    数据使用
    API Key 仅用于向 api.deepseek.com 发起余额查询。所有数据传输通过 HTTPS 加密。查询到的余额数据仅存储在设备本地。

    数据共享
    我们不会将您的任何数据分享、出售或传输给任何第三方。

    数据安全
    • API Key 使用 Android EncryptedSharedPreferences（AES-256）存储
    • 所有网络请求通过 HTTPS 加密
    • 应用备份已禁用
    • 日志输出自动脱敏 API Key

    数据删除
    您可以在「数据管理」页面随时删除所有数据。卸载 App 会自动清除所有本地数据。

    权限说明
    • INTERNET — 查询 API 余额
    • FOREGROUND_SERVICE — 后台定时刷新
    • POST_NOTIFICATIONS — 余额告警通知
    • RECEIVE_BOOT_COMPLETED — 开机自启
    • SCHEDULE_EXACT_ALARM — 精确定时
    • WAKE_LOCK — 防 CPU 休眠

    第三方服务
    仅连接 DeepSeek 官方 API，无第三方分析/广告/追踪 SDK。

    如有隐私相关问题，请通过 GitHub Issues 联系我们。

    本政策适用于钱包哨兵 Android 应用（包名：com.balancesentinel.app）。
""".trimIndent()

// ═══════════════════════════════════════════════════════════
// 数据管理入口
// ═══════════════════════════════════════════════════════════

@Composable
private fun DataManagementEntryRow(onClick: () -> Unit) {
    val dataLabel = stringResource(R.string.settings_data_management)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = dataLabel
                }
                .clickable { onClick() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CustomIcons.SaveAlt, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.settings_data_management),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_data_management_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
    }
}

