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
import com.balancesentinel.app.R
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.ConfigManager
import com.balancesentinel.app.data.repository.DataExporter
import com.balancesentinel.app.data.repository.LogExporter
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel

/**
 * 备份与迁移子页面 — 历史数据 + 配置的导入导出 + 调试报告。
 * 所有 SAF 文件选择器在此页面管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    // ── 历史数据导出 launcher ──
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

    // ── 历史数据导入 launcher ──
    val importDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = DataExporter.importAndApply(context, uri)
            if (result != null) {
                val (summaries, records) = result
                if (summaries > 0 || records > 0) {
                    Toast.makeText(context, context.getString(R.string.data_import_history_success, summaries, records), Toast.LENGTH_SHORT).show()
                    viewModel.loadStats()
                } else {
                    Toast.makeText(context, context.getString(R.string.data_import_history_empty), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, context.getString(R.string.data_import_history_fail), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, context.getString(R.string.data_config_export_warning), Toast.LENGTH_LONG).show()
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
                val skipped = ConfigManager.applyConfigDirectly(context, config)
                if (skipped > 0) {
                    Toast.makeText(context, context.getString(R.string.data_config_import_skipped, skipped), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.data_config_import_success, config.accounts.size), Toast.LENGTH_SHORT).show()
                }
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
                title = { Text(stringResource(R.string.data_backup_title)) },
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
            // ── 历史数据 ──
            SectionLabel(stringResource(R.string.data_backup_section_history))

            BackupActionCard(
                icon = { Icon(CustomIcons.BarChart, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_export_history_title),
                description = stringResource(R.string.data_export_history_desc),
                buttonText = stringResource(R.string.data_export_btn),
                onAction = { exportDataLauncher.launch("wallet_sentinel_data.json") }
            )

            BackupActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_import_history_title),
                description = stringResource(R.string.data_import_history_desc),
                buttonText = stringResource(R.string.data_import_history_btn),
                onAction = { importDataLauncher.launch(arrayOf("application/json", "*/*")) }
            )

            // ── 配置 ──
            SectionLabel(stringResource(R.string.data_backup_section_config))

            BackupActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_export_config_title),
                description = stringResource(R.string.data_export_config_desc),
                buttonText = stringResource(R.string.data_export_config_btn),
                onAction = { exportConfigLauncher.launch("wallet_sentinel_config.json") }
            )

            BackupActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_import_config_title),
                description = stringResource(R.string.data_import_config_desc),
                buttonText = stringResource(R.string.data_import_config_btn),
                onAction = { importConfigLauncher.launch(arrayOf("application/json", "*/*")) }
            )

            // ── 调试 ──
            SectionLabel(stringResource(R.string.data_backup_section_debug))

            BackupActionCard(
                icon = { Icon(CustomIcons.SaveAlt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_debug_report_title),
                description = stringResource(R.string.data_debug_report_desc),
                buttonText = stringResource(R.string.data_debug_report_btn),
                onAction = exportDebugReport
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun BackupActionCard(
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
