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
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.ui.CustomIcons

/**
 * 编辑账户对话框
 */
@Composable
fun EditAccountDialog(
    account: AccountInfo,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var label by remember { mutableStateOf(account.label) }
    var apiKey by remember { mutableStateOf(account.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(label, apiKey) },
                enabled = label.isNotBlank() && apiKey.isNotBlank() &&
                          ProviderConfigs.validateApiKey(account.providerType, apiKey),
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
