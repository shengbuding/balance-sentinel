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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.repository.BackupImportPlan
import com.balancesentinel.app.data.repository.ImportMode
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.data.repository.DataExporter
import com.balancesentinel.app.data.repository.LogExporter
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.DataManagementUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 备份与迁移子页面 — 历史数据 + 配置的导入导出 + 调试报告。
 * 所有 SAF 文件选择器在此页面管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit,
    onConfigImported: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isImporting by remember { mutableStateOf(false) }
    var isConfigImporting by remember { mutableStateOf(false) }

    // 配置导出选项
    var showConfigExportDialog by remember { mutableStateOf(false) }
    var showTokenWarningDialog by remember { mutableStateOf(false) }
    var includeTokensInExport by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    // ── 历史数据导出 launcher ──
    val exportDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            if (DataExporter.hasData(context)) {
                scope.launch {
                    val ok = viewModel.exportHistory(uri)
                    if (ok) {
                        Toast.makeText(context, context.getString(R.string.data_export_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.data_export_fail), Toast.LENGTH_SHORT).show()
                    }
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
            isImporting = true
            scope.launch {
                val result = viewModel.importHistory(uri)
                isImporting = false
                if (result != null) {
                    val detail = context.getString(
                        R.string.data_import_history_detail,
                        result.summariesInFile, result.summariesImported,
                        result.recordsInFile, result.recordsImported,
                        result.snapshotsInFile, result.snapshotsImported,
                        result.logsInFile, result.logsImported
                    )
                    Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.data_import_history_fail), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── 配置导出 launcher ──
    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = viewModel.exportConfiguration(uri, includeTokensInExport)
                if (ok) {
                    Toast.makeText(context, context.getString(R.string.data_config_export_success), Toast.LENGTH_SHORT).show()
                    if (includeTokensInExport) {
                        Toast.makeText(context, context.getString(R.string.data_config_export_token_warning), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.data_config_export_warning), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.data_config_export_fail), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── 配置导入 launcher ──
    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isConfigImporting = true
            scope.launch {
                val parsed = viewModel.previewConfiguration(uri)
                isConfigImporting = false
                if (!parsed) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.data_config_import_fail),
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
                buttonText = if (isImporting) stringResource(R.string.data_importing) else stringResource(R.string.data_import_history_btn),
                onAction = { importDataLauncher.launch(arrayOf("application/json", "*/*")) },
                loading = isImporting
            )

            // ── 配置 ──
            SectionLabel(stringResource(R.string.data_backup_section_config))

            BackupActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_export_config_title),
                description = stringResource(R.string.data_export_config_desc),
                buttonText = stringResource(R.string.data_export_config_btn),
                onAction = { showConfigExportDialog = true }
            )

            BackupActionCard(
                icon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.data_import_config_title),
                description = stringResource(R.string.data_import_config_desc),
                buttonText = if (isConfigImporting) {
                    stringResource(R.string.data_importing)
                } else {
                    stringResource(R.string.data_import_config_btn)
                },
                onAction = { importConfigLauncher.launch(arrayOf("application/json", "*/*")) },
                loading = isConfigImporting
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

        // ── 配置导出选项对话框 ──
        if (showConfigExportDialog) {
            AlertDialog(
                onDismissRequest = { showConfigExportDialog = false },
                title = { Text(stringResource(R.string.data_config_export_dialog_title)) },
                text = { Text(stringResource(R.string.data_config_export_dialog_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showConfigExportDialog = false
                        includeTokensInExport = false
                        exportConfigLauncher.launch("wallet_sentinel_config.json")
                    }) {
                        Text(stringResource(R.string.data_config_export_no_tokens))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showConfigExportDialog = false
                        showTokenWarningDialog = true
                    }) {
                        Text(
                            stringResource(R.string.data_config_export_with_tokens),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }

        // ── Token 风险警告对话框 ──
        if (showTokenWarningDialog) {
            AlertDialog(
                onDismissRequest = { showTokenWarningDialog = false },
                title = {
                    Text(
                        stringResource(R.string.data_config_export_token_risk_title),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = { Text(stringResource(R.string.data_config_export_token_risk_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showTokenWarningDialog = false
                        includeTokensInExport = true
                        exportConfigLauncher.launch("wallet_sentinel_config.json")
                    }) {
                        Text(
                            stringResource(R.string.data_config_export_token_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTokenWarningDialog = false }) {
                        Text(stringResource(R.string.data_cancel))
                    }
                }
            )
        }

        uiState.pendingImportPlan
            ?.takeUnless { uiState.replaceConfirmationRequired }
            ?.let { plan ->
                ConfigImportPreviewDialog(
                    plan = plan,
                    uiState = uiState,
                    onModeSelected = { mode ->
                        scope.launch { viewModel.selectImportMode(mode) }
                    },
                    onScriptEnabled = viewModel::setImportedScriptEnabled,
                    onOriginAuthorized = viewModel::setImportOriginAuthorized,
                    onDismiss = viewModel::dismissImportPreview,
                    onApply = {
                        val accountCount = plan.finalAccounts.size
                        if (viewModel.requestApplyImport()) {
                            onConfigImported()
                            Toast.makeText(
                                context,
                                context.getString(R.string.data_config_import_success, accountCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

        if (uiState.replaceConfirmationRequired) {
            val deletedCount = uiState.pendingImportPlan?.deletedCount ?: 0
            AlertDialog(
                modifier = Modifier.testTag("replace_confirm_dialog"),
                onDismissRequest = {
                    scope.launch { viewModel.selectImportMode(ImportMode.REPLACE_ALL) }
                },
                title = { Text(stringResource(R.string.data_config_import_replace_title)) },
                text = {
                    Text(stringResource(R.string.data_config_import_replace_message, deletedCount))
                },
                confirmButton = {
                    TextButton(
                        modifier = Modifier.testTag("replace_confirm_apply"),
                        onClick = {
                            val accountCount = uiState.pendingImportPlan?.finalAccounts?.size ?: 0
                            if (viewModel.confirmReplaceImport()) {
                                onConfigImported()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.data_config_import_success, accountCount),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text(
                            stringResource(R.string.data_config_import_replace_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch { viewModel.selectImportMode(ImportMode.REPLACE_ALL) }
                    }) {
                        Text(stringResource(R.string.data_cancel))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigImportPreviewDialog(
    plan: BackupImportPlan,
    uiState: DataManagementUiState,
    onModeSelected: (ImportMode) -> Unit,
    onScriptEnabled: (String, Boolean) -> Unit,
    onOriginAuthorized: (String, WebOrigin, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.testTag("config_import_preview"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_config_import_preview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ImportMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            modifier = Modifier.testTag(
                                if (mode == ImportMode.MERGE) "import_mode_merge" else "import_mode_replace"
                            ),
                            selected = plan.mode == mode,
                            onClick = { onModeSelected(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ImportMode.entries.size),
                            label = {
                                Text(
                                    stringResource(
                                        if (mode == ImportMode.MERGE) {
                                            R.string.data_config_import_mode_merge
                                        } else {
                                            R.string.data_config_import_mode_replace
                                        }
                                    )
                                )
                            }
                        )
                    }
                }

                ImportStat(R.string.data_config_import_stat_matched, plan.matchedUpdatedCount)
                ImportStat(R.string.data_config_import_stat_retained, plan.retainedCredentialCount)
                ImportStat(R.string.data_config_import_stat_created, plan.createdCount)
                ImportStat(R.string.data_config_import_stat_skipped, plan.skippedCount)
                ImportStat(R.string.data_config_import_stat_conflicts, plan.conflictCount)
                ImportStat(R.string.data_config_import_stat_deleted, plan.deletedCount)

                if (plan.blockingReasons.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.data_config_import_blocked_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    plan.blockingReasons.forEach { reason ->
                        Text(
                            stringResource(blockingReasonResource(reason)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (plan.scriptAuthorizations.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.data_config_import_scripts_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    plan.scriptAuthorizations.forEach { authorization ->
                        val accountLabel = plan.finalAccounts
                            .firstOrNull { it.id == authorization.accountId }
                            ?.label
                            .orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = authorization.accountId in uiState.enabledImportedScripts,
                                onCheckedChange = { onScriptEnabled(authorization.accountId, it) },
                                enabled = authorization.staticallyDeterminable
                            )
                            Text(
                                stringResource(R.string.data_config_import_script_enable, accountLabel),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (!authorization.staticallyDeterminable) {
                            Text(
                                stringResource(R.string.data_config_import_script_not_static),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        authorization.requiredExtraOrigins
                            .sortedWith(compareBy(WebOrigin::scheme, WebOrigin::host, WebOrigin::port))
                            .forEach { origin ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = origin in uiState.authorizedImportOrigins[
                                            authorization.accountId
                                        ].orEmpty(),
                                        onCheckedChange = {
                                            onOriginAuthorized(authorization.accountId, origin, it)
                                        },
                                        enabled = authorization.staticallyDeterminable
                                    )
                                    Text(canonicalOrigin(origin), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("import_apply"),
                onClick = onApply,
                enabled = plan.canApply
            ) {
                Text(stringResource(R.string.data_config_import_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.data_cancel))
            }
        }
    )
}

@Composable
private fun ImportStat(labelResource: Int, value: Int) {
    Text(
        stringResource(labelResource, value),
        style = MaterialTheme.typography.bodySmall
    )
}

private fun blockingReasonResource(reason: String): Int = when (reason) {
    "credentials_required" -> R.string.data_config_import_block_credentials
    "conflicts_present" -> R.string.data_config_import_block_conflicts
    "incomplete_accounts" -> R.string.data_config_import_block_incomplete
    "script_inspection_required" -> R.string.data_config_import_block_script
    else -> R.string.data_config_import_block_unknown
}

private fun canonicalOrigin(origin: WebOrigin): String {
    val host = if (':' in origin.host) "[${origin.host}]" else origin.host
    val defaultPort = origin.scheme == "https" && origin.port == 443
    return "${origin.scheme}://$host${if (defaultPort) "" else ":${origin.port}"}"
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
    onAction: () -> Unit,
    loading: Boolean = false
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
                shape = RoundedCornerShape(8.dp),
                enabled = !loading
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(buttonText)
            }
        }
    }
}
