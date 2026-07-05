package com.balancesentinel.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.PendingAction

/**
 * 清除数据子页面 — 5 类存储数据的清空操作。
 * 每个操作都需要二次确认，防止误触。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearDataScreen(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit
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

    // 确认对话框
    uiState.pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissAction() },
            title = { Text(stringResource(R.string.data_confirm_title)) },
            text = {
                Text(
                    when (action) {
                        PendingAction.ClearRawRecords -> stringResource(R.string.data_confirm_clear_records)
                        PendingAction.ClearDailySummaries -> stringResource(R.string.data_confirm_clear_summaries)
                        PendingAction.ClearUsageSnapshots -> stringResource(R.string.data_confirm_clear_usage)
                        PendingAction.ClearRefreshLogs -> stringResource(R.string.data_confirm_clear_logs)
                        PendingAction.ClearWidgetErrors -> stringResource(R.string.data_confirm_clear_widget_errors)
                        else -> ""
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.executeAction(action) }) {
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
                title = { Text(stringResource(R.string.data_clear_title)) },
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
            ClearActionCard(
                title = stringResource(R.string.data_clear_raw_records_title),
                description = stringResource(R.string.data_clear_raw_records_desc),
                buttonText = stringResource(R.string.data_clear_raw_records_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearRawRecords) }
            )

            ClearActionCard(
                title = stringResource(R.string.data_clear_summaries_title),
                description = stringResource(R.string.data_clear_summaries_desc),
                buttonText = stringResource(R.string.data_clear_summaries_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearDailySummaries) }
            )

            ClearActionCard(
                title = stringResource(R.string.data_clear_usage_title),
                description = stringResource(R.string.data_clear_usage_desc),
                buttonText = stringResource(R.string.data_clear_usage_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearUsageSnapshots) }
            )

            ClearActionCard(
                title = stringResource(R.string.data_clear_logs_title),
                description = stringResource(R.string.data_clear_logs_desc),
                buttonText = stringResource(R.string.data_clear_logs_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearRefreshLogs) }
            )

            ClearActionCard(
                title = stringResource(R.string.data_clear_widget_errors_title),
                description = stringResource(R.string.data_clear_widget_errors_desc),
                buttonText = stringResource(R.string.data_clear_widget_errors_btn),
                onAction = { viewModel.requestAction(PendingAction.ClearWidgetErrors) }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ClearActionCard(
    title: String,
    description: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Delete, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
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
