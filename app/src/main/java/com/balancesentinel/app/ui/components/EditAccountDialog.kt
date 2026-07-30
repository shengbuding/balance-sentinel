package com.balancesentinel.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.balance.PresetScripts
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.ui.CustomIcons

/**
 * 编辑账户对话框
 */
@Composable
fun EditAccountDialog(
    account: AccountInfo,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Map<String, String>, String?) -> Unit
) {
    var label by remember { mutableStateOf(account.label) }
    var apiKey by remember { mutableStateOf(account.apiKey) }
    var baseUrl by remember { mutableStateOf(account.extraSettings["baseUrl"] ?: "") }
    var usageScript by remember { mutableStateOf(account.usageScript ?: "") }
    var showKey by remember { mutableStateOf(false) }
    // 如果账户已有脚本，默认显示脚本编辑器
    var showScriptEditor by remember { mutableStateOf(!account.usageScript.isNullOrBlank()) }
    val clipboardManager = LocalClipboardManager.current
    val isCustom = account.providerType == ProviderType.CUSTOM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑账户") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 供应商信息（只读）
                OutlinedTextField(
                    value = account.providerType.displayName,
                    onValueChange = {},
                    label = { Text("供应商") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = false
                )

                // 账户标签
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.add_account_label)) },
                    placeholder = { Text(stringResource(R.string.add_account_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.add_account_key_label)) },
                    placeholder = { Text(ProviderConfigs.getApiKeyHint(account.providerType)) },
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
                    shape = RoundedCornerShape(8.dp),
                    isError = apiKey.isNotBlank() && !ProviderConfigs.validateApiKey(account.providerType, apiKey)
                )

                // 验证提示
                if (apiKey.isNotBlank() && !ProviderConfigs.validateApiKey(account.providerType, apiKey)) {
                    Text(
                        text = "API Key格式不正确",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 自定义供应商的URL输入
                if (isCustom) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("API Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        isError = baseUrl.isNotBlank() && !baseUrl.startsWith("http")
                    )

                    // URL验证提示
                    if (baseUrl.isNotBlank() && !baseUrl.startsWith("http")) {
                        Text(
                            text = "URL必须以 http:// 或 https:// 开头",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // 自定义脚本开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "自定义余额查询脚本",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = showScriptEditor,
                            onCheckedChange = { showScriptEditor = it }
                        )
                    }

                    // 自定义脚本编辑器
                    if (showScriptEditor) {
                        OutlinedTextField(
                            value = usageScript,
                            onValueChange = { usageScript = it },
                            label = { Text("查询脚本") },
                            placeholder = {
                                Text(
                                    """({
  request: {
    url: "{{baseUrl}}/v1/usage",
    method: "GET",
    headers: { "Authorization": "Bearer {{apiKey}}" }
  },
  extractor: function(response) {
    return {
      remaining: response.balance,
      unit: "USD"
    };
  }
})""",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )

                        // 脚本说明
                        Text(
                            text = "支持模板变量: {{apiKey}}, {{baseUrl}}\n" +
                                   "返回格式: { remaining, unit, isValid }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 加载预置脚本按钮
                        var showPresetMenu by remember { mutableStateOf(false) }
                        Box {
                            Button(
                                onClick = { showPresetMenu = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Text("加载预置脚本")
                            }
                            DropdownMenu(
                                expanded = showPresetMenu,
                                onDismissRequest = { showPresetMenu = false }
                            ) {
                                // 通用脚本
                                DropdownMenuItem(
                                    text = { Text("通用脚本") },
                                    onClick = {
                                        showPresetMenu = false
                                        usageScript = PresetScripts.getCustomTemplate().code
                                    }
                                )
                                // 根据baseUrl自动检测
                                if (baseUrl.isNotBlank()) {
                                    val preset = PresetScripts.getPresetScript(baseUrl)
                                    if (preset != null) {
                                        DropdownMenuItem(
                                            text = { Text("自动检测 (${baseUrl.take(30)}...)") },
                                            onClick = {
                                                showPresetMenu = false
                                                usageScript = preset.code
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val extraSettings = if (isCustom) {
                mapOf("baseUrl" to baseUrl)
            } else {
                emptyMap()
            }
            val isCustomValid = !isCustom || (baseUrl.isNotBlank() && baseUrl.startsWith("http"))
            // 保存逻辑：
            // - 如果开启脚本编辑器且有内容，保存脚本
            // - 如果开启脚本编辑器但内容为空，清空脚本（保存null）
            // - 如果关闭脚本编辑器，清空脚本（保存null）
            val scriptToSave = if (isCustom && showScriptEditor) {
                usageScript.ifBlank { null }
            } else {
                null
            }

            Button(
                onClick = { onConfirm(label, apiKey, extraSettings, scriptToSave) },
                enabled = label.isNotBlank() && apiKey.isNotBlank() &&
                          ProviderConfigs.validateApiKey(account.providerType, apiKey) &&
                          isCustomValid,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        }
    )
}
