package com.balancesentinel.app.ui.console

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.console.ConsolePageCaptureSession
import com.balancesentinel.app.ui.CustomIcons

/**
 * 新增 / 修改控制台的共用表单。
 *
 * 和旧版「添加自定义平台」相比，这里只强制三件事：名称、网站、可用的 HTTPS 地址。
 * 登录页、余额页、URL 模式要么手填，要么用「浏览并标记」边点边录 —— 都不用懂正则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolePlatformFormScreen(
    initialDraft: ConsolePlatformDraft,
    title: String,
    submitLabel: String,
    platformId: String,
    isPreset: Boolean,
    onBack: () -> Unit,
    onCancel: (() -> Unit)?,
    onSubmit: (ConsolePlatform) -> Unit
) {
    var draft by remember { mutableStateOf(initialDraft) }
    var showErrors by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var probeUrl by remember { mutableStateOf<String?>(null) }

    val errors = if (showErrors) draft.errors() else emptySet()

    if (probeUrl != null) {
        ConsolePageProbeScreen(
            startUrl = probeUrl.orEmpty(),
            onBack = { probeUrl = null },
            onApply = { capture ->
                draft = draft.applyCapture(capture)
                probeUrl = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.console_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isPreset) {
                Text(
                    text = stringResource(R.string.console_edit_preset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.console_platform_name_label)) },
                placeholder = { Text(stringResource(R.string.console_platform_name_hint)) },
                isError = ConsoleDraftError.NameMissing in errors,
                supportingText = if (ConsoleDraftError.NameMissing in errors) {
                    { Text(stringResource(R.string.console_platform_name_required)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            OutlinedTextField(
                value = draft.website,
                onValueChange = { draft = draft.copy(website = it) },
                label = { Text(stringResource(R.string.console_website_label)) },
                placeholder = { Text(stringResource(R.string.console_website_hint)) },
                isError = ConsoleDraftError.WebsiteInvalid in errors ||
                    ConsoleDraftError.UrlsMissing in errors,
                supportingText = if (ConsoleDraftError.WebsiteInvalid in errors) {
                    { Text(stringResource(R.string.console_website_invalid)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // 浏览并标记：整个功能最省事的一步
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.console_browse_and_mark),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.console_browse_and_mark_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val startUrl = draft.browseUrl
                    val canProbe = startUrl != null
                    OutlinedButton(
                        onClick = { if (canProbe) probeUrl = startUrl },
                        enabled = canProbe,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(CustomIcons.Public, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (canProbe) {
                                stringResource(R.string.console_browse_and_mark)
                            } else {
                                stringResource(R.string.console_probe_need_url)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.loginUrl,
                onValueChange = { draft = draft.copy(loginUrl = it) },
                label = { Text(stringResource(R.string.console_login_url_label)) },
                placeholder = { Text("https://example.com/login") },
                isError = ConsoleDraftError.LoginUrlInvalid in errors,
                supportingText = if (ConsoleDraftError.LoginUrlInvalid in errors) {
                    { Text(stringResource(R.string.console_url_http_required)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            OutlinedTextField(
                value = draft.dashboardUrl,
                onValueChange = { draft = draft.copy(dashboardUrl = it) },
                label = { Text(stringResource(R.string.console_dashboard_url_label)) },
                placeholder = { Text("https://example.com/overview") },
                isError = ConsoleDraftError.DashboardUrlInvalid in errors,
                supportingText = if (ConsoleDraftError.DashboardUrlInvalid in errors) {
                    { Text(stringResource(R.string.console_url_required)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (showAdvanced) {
                        stringResource(R.string.console_collapse)
                    } else {
                        stringResource(R.string.console_expand)
                    }
                )
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = draft.successPatterns,
                    onValueChange = { draft = draft.copy(successPatterns = it) },
                    label = { Text(stringResource(R.string.console_success_pattern_label)) },
                    placeholder = { Text("/overview,/console") },
                    supportingText = { Text(stringResource(R.string.console_success_pattern_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = draft.loginPatterns,
                    onValueChange = { draft = draft.copy(loginPatterns = it) },
                    label = { Text(stringResource(R.string.console_login_page_patterns_label)) },
                    placeholder = { Text("/login,/sign_in") },
                    supportingText = { Text(stringResource(R.string.console_login_page_patterns_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                label = { Text(stringResource(R.string.console_description_label)) },
                placeholder = { Text(stringResource(R.string.console_description_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    showErrors = true
                    if (draft.isValid) {
                        onSubmit(draft.toPlatform(platformId = platformId, isPreset = isPreset))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(submitLabel)
            }

            if (onCancel != null) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.console_cancel))
                }
            }

            if (ConsoleDraftError.UrlsMissing in errors) {
                Text(
                    text = stringResource(R.string.console_website_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 新控制台使用的 id：时间戳足够区分，也不会和预设 id 撞车。 */
internal fun newCustomPlatformId(now: Long = System.currentTimeMillis()): String = "custom_$now"
