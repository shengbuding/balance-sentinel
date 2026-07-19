package com.balancesentinel.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.ui.CustomIcons

/**
 * 添加账户对话框（支持多供应商）
 */
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, ProviderType) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ProviderType.DEEPSEEK) }
    var label by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // 供应商列表（只显示可用的）
    val availableProviders = remember {
        listOf(
            ProviderType.DEEPSEEK,
            ProviderType.MOONSHOT,
            ProviderType.DOUBAO,
            ProviderType.BAICHUAN,
            ProviderType.QWEN,
            ProviderType.ZHIPU,
            ProviderType.WENXIN,
            ProviderType.OPENAI,
            ProviderType.ANTHROPIC,
            ProviderType.GEMINI,
            ProviderType.MISTRAL,
            ProviderType.COHERE
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 供应商选择
                Box {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        label = { Text("供应商") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, "展开")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        availableProviders.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    selectedProvider = provider
                                    expanded = false
                                }
                            )
                        }
                    }
                }

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
                    placeholder = { Text(ProviderConfigs.getApiKeyHint(selectedProvider)) },
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
                    isError = apiKey.isNotBlank() && !ProviderConfigs.validateApiKey(selectedProvider, apiKey)
                )

                // 验证提示
                if (apiKey.isNotBlank() && !ProviderConfigs.validateApiKey(selectedProvider, apiKey)) {
                    Text(
                        text = "API Key格式不正确",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(label, apiKey, selectedProvider) },
                enabled = label.isNotBlank() && apiKey.isNotBlank() &&
                          ProviderConfigs.validateApiKey(selectedProvider, apiKey),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.home_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        }
    )
}
