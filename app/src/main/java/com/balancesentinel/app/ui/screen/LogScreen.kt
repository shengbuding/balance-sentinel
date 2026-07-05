package com.balancesentinel.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import com.balancesentinel.app.ui.theme.WalletColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.LogViewModel
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.balancesentinel.app.util.FormatUtils
import com.balancesentinel.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: LogViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let { path ->
            val msg = if (path.startsWith("/")) context.getString(R.string.log_exported_toast, java.io.File(path).name) else path
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearExportResult()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportLogs() }) {
                        Icon(CustomIcons.SaveAlt,
                            contentDescription = stringResource(R.string.log_export),
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.log_clear),
                            tint = MaterialTheme.colorScheme.onPrimary)
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
            // ── 日志类型筛选 ──
            LogTypeFilterRow(
                selected = uiState.selectedLogType,
                onSelect = { viewModel.selectLogType(it) }
            )

            // ── 刷新日志 ──
            RefreshLogSection(
                logs = uiState.refreshLogs,
                missedCount = uiState.missedCount,
                logMaxEntries = uiState.logMaxEntries,
                onSetMax = { viewModel.setLogMax(it) },
                onClear = { viewModel.clearLogs() },
                onExport = { viewModel.exportLogs() }
            )

            // ── 崩溃日志 ──
            if (uiState.crashLogs.isNotEmpty()) {
                CrashLogCard(
                    crashes = uiState.crashLogs,
                    onClear = { viewModel.clearCrashes() }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 刷新日志
// ═══════════════════════════════════════════════════════════

@Composable
private fun RefreshLogSection(
    logs: List<RefreshLogEntry>,
    missedCount: Int,
    logMaxEntries: Int,
    onSetMax: (Int) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLogs = if (expanded) logs else logs.take(10)
    var showMaxEditor by remember { mutableStateOf(false) }
    var maxInput by remember(logMaxEntries) { mutableStateOf(logMaxEntries.toString()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(CustomIcons.History, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.log_total_records, logs.size),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (missedCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)) {
                            Text(stringResource(R.string.log_missed_count, missedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Row {
                    IconButton(onClick = onExport) {
                        Icon(CustomIcons.SaveAlt,
                            contentDescription = stringResource(R.string.log_export),
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.log_clear),
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // 日志上限设置行
            if (!showMaxEditor) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button }
                        .clickable { showMaxEditor = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.log_max_limit, logMaxEntries),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.log_modify), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = maxInput,
                        onValueChange = { maxInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.log_entries_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val num = maxInput.toIntOrNull()
                        if (num != null && num in 10..1000) {
                            onSetMax(num)
                            showMaxEditor = false
                        }
                    }, shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.settings_confirm))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = {
                        maxInput = logMaxEntries.toString()
                        showMaxEditor = false
                    }) {
                        Text(stringResource(R.string.home_cancel))
                    }
                }
                Text(stringResource(R.string.log_range), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (logs.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.log_no_logs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                displayLogs.forEachIndexed { index, entry ->
                    RefreshLogItem(entry)
                    if (index < displayLogs.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            if (logs.size > 10) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (expanded) stringResource(R.string.log_collapse)
                        else stringResource(R.string.log_show_all, logs.size),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RefreshLogItem(entry: RefreshLogEntry) {
    when (entry.type) {
        RefreshLogType.MANUAL -> ManualAutoLogItem(entry)
        RefreshLogType.AUTO -> ManualAutoLogItem(entry)
        RefreshLogType.SCHEDULE -> ScheduleLogItem(entry)
        RefreshLogType.MISSED -> MissedLogItem(entry)
        RefreshLogType.SERVICE_DIED -> ServiceDiedLogItem(entry)
        RefreshLogType.SERVICE_START -> ServiceStartLogItem(entry)
        RefreshLogType.WATCHDOG -> WatchdogLogItem(entry)
    }
}

@Composable
private fun ManualAutoLogItem(entry: RefreshLogEntry) {
    val cs = FormatUtils.currencySymbol(entry.currency)
    val isManual = entry.type == RefreshLogType.MANUAL
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp),
                color = if (isManual) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else WalletColors.successBg) {
                Text(
                    stringResource(if (isManual) R.string.log_manual_label else R.string.log_auto_label),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                    color = if (isManual) MaterialTheme.colorScheme.primary else WalletColors.success,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("$cs${FormatUtils.formatAmount(entry.totalBalance)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (entry.message.isNotEmpty()) {
                    Text(entry.message, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(formatTimeAgo(entry.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScheduleLogItem(entry: RefreshLogEntry) {
    val isDegraded = entry.alarmMethod == "inexact"
    val isFailed = entry.alarmMethod == "failed"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp),
                color = if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                else if (isDegraded) WalletColors.warningBgTransparent
                else WalletColors.neutralBg) {
                Text(
                    stringResource(when { isFailed -> R.string.log_failed_schedule; isDegraded -> R.string.log_degraded_schedule; else -> R.string.log_scheduled }),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                    color = when { isFailed -> MaterialTheme.colorScheme.error
                        isDegraded -> WalletColors.warning; else -> WalletColors.neutralGrey },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(formatTimeAgo(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(entry.message, style = MaterialTheme.typography.bodySmall,
                color = if (isFailed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (entry.intervalSeconds > 0) {
            Text("间隔: ${FormatUtils.formatInterval(entry.intervalSeconds)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MissedLogItem(entry: RefreshLogEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)) {
                Text(stringResource(R.string.log_missed_refresh),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(formatTimeAgo(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(entry.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        }
        if (entry.missReason.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(entry.missReason, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        }
        if (entry.expectedTime > 0) {
            Text("预定: ${FormatUtils.formatFullTime(entry.expectedTime)} · 间隔: ${FormatUtils.formatInterval(entry.intervalSeconds)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServiceDiedLogItem(entry: RefreshLogEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)) {
                Text(stringResource(R.string.log_service_died),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(formatTimeAgo(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(entry.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        }
        if (entry.missReason.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(entry.missReason, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        }
    }
}

@Composable
private fun ServiceStartLogItem(entry: RefreshLogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp),
                color = WalletColors.successBg) {
                Text(stringResource(R.string.log_service_start_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium, color = WalletColors.success,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(entry.message.ifEmpty { stringResource(R.string.log_service_start_message) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatTimeAgo(entry.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WatchdogLogItem(entry: RefreshLogEntry) {
    val isFailed = entry.message.contains("失败")
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp),
                color = if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                else WalletColors.warningBgTransparent) {
                Text(stringResource(R.string.log_watchdog_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailed) MaterialTheme.colorScheme.error else WalletColors.warning,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(formatTimeAgo(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(entry.message, style = MaterialTheme.typography.bodySmall,
                color = if (isFailed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(stringResource(R.string.log_crash_log, crashes.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)
                Row {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(
                            crashes.joinToString("\n---\n") { it.fullStack }))
                    }) {
                        Icon(CustomIcons.ContentCopy,
                            stringResource(R.string.settings_copy),
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Delete,
                            stringResource(R.string.settings_clear),
                            Modifier.size(18.dp),
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
// 工具函数
// ═══════════════════════════════════════════════════════════

private fun formatTimeAgo(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        else -> FormatUtils.formatFullTime(timestamp)
    }
}

// ═══════════════════════════════════════════════════════════
// 日志类型筛选
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogTypeFilterRow(
    selected: RefreshLogType?,
    onSelect: (RefreshLogType?) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = {
                Text(
                    stringResource(R.string.log_all),
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
        listOf(
            RefreshLogType.MANUAL to R.string.log_manual,
            RefreshLogType.AUTO to R.string.log_auto,
            RefreshLogType.SCHEDULE to R.string.log_schedule,
            RefreshLogType.MISSED to R.string.log_missed,
            RefreshLogType.SERVICE_DIED to R.string.log_service,
            RefreshLogType.WATCHDOG to R.string.log_watchdog
        ).forEach { (type, resId) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
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
