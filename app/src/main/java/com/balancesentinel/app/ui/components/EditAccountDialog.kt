package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.balance.PresetScripts
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo

/**
 * 编辑账户对话框
 */
@Composable
fun EditAccountDialog(
    account: AccountInfo,
    onDismiss: () -> Unit,
    onConfirm: (AccountDraft) -> Unit
) {
    var label by remember(account.id, account.revision) { mutableStateOf(account.label) }
    val fields = remember(account.providerType) {
        ProviderConfigs.getConfigFields(account.providerType)
    }
    var values by remember(account.id, account.revision) {
        mutableStateOf(
            fields.associate { field ->
                val value = when (field.storage) {
                    ConfigFieldStorage.PRIMARY_CREDENTIAL -> {
                        if (field.key == "apiKey") account.apiKey else field.defaultValue.orEmpty()
                    }
                    ConfigFieldStorage.EXTRA_CREDENTIAL -> {
                        account.extraCredentials[field.key] ?: field.defaultValue.orEmpty()
                    }
                    ConfigFieldStorage.SETTING -> {
                        account.extraSettings[field.key] ?: field.defaultValue.orEmpty()
                    }
                }
                field.key to value
            }
        )
    }
    var usageScript by remember(account.id, account.revision) {
        mutableStateOf(account.usageScript.orEmpty())
    }
    var usageScriptEnabled by remember(account.id, account.revision) {
        mutableStateOf(account.usageScriptEnabled)
    }
    val isCustom = account.providerType == ProviderType.CUSTOM
    val apiKey = values["apiKey"].orEmpty()
    val baseUrl = values["baseUrl"].orEmpty()
    val fieldsValid = ProviderConfigs.validateFieldValues(account.providerType, values)
    val apiKeyValid = ProviderConfigs.validateApiKey(account.providerType, apiKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑账户") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = account.providerType.displayName,
                    onValueChange = {},
                    label = { Text("供应商") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = false
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.add_account_label)) },
                    placeholder = { Text(stringResource(R.string.add_account_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                ProviderCredentialFields(
                    fields = fields,
                    values = values,
                    onValueChange = { key, value -> values = values + (key to value) }
                )

                if (apiKey.isNotBlank() && !apiKeyValid) {
                    Text(
                        text = "API Key格式不正确",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isCustom) {
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
                            checked = usageScriptEnabled,
                            onCheckedChange = { usageScriptEnabled = it }
                        )
                    }

                    if (usageScriptEnabled) {
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

                        Text(
                            text = "支持模板变量: {{apiKey}}, {{baseUrl}}\n" +
                                "返回格式: { remaining, unit, isValid }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

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
                                DropdownMenuItem(
                                    text = { Text("通用脚本") },
                                    onClick = {
                                        showPresetMenu = false
                                        usageScript = PresetScripts.getCustomTemplate().code
                                    }
                                )
                                if (baseUrl.isNotBlank()) {
                                    PresetScripts.getPresetScript(baseUrl)?.let { preset ->
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
            Button(
                onClick = {
                    val knownKeys = fields.mapTo(mutableSetOf()) { it.key }
                    val extraCredentials = account.extraCredentials
                        .filterKeys { it !in knownKeys } +
                        ProviderConfigs.valuesForStorage(
                            account.providerType,
                            values,
                            ConfigFieldStorage.EXTRA_CREDENTIAL
                        )
                    val extraSettings = account.extraSettings
                        .filterKeys { it !in knownKeys } +
                        ProviderConfigs.valuesForStorage(
                            account.providerType,
                            values,
                            ConfigFieldStorage.SETTING
                        )
                    onConfirm(
                        AccountDraft(
                            label = label,
                            apiKey = apiKey,
                            providerType = account.providerType,
                            extraCredentials = extraCredentials,
                            extraSettings = extraSettings,
                            usageScript = if (isCustom) {
                                usageScript.ifBlank { null }
                            } else {
                                account.usageScript
                            },
                            usageScriptEnabled = if (isCustom) {
                                usageScriptEnabled
                            } else {
                                account.usageScriptEnabled
                            },
                            authorizedScriptOrigins = account.authorizedScriptOrigins
                        )
                    )
                },
                enabled = label.isNotBlank() && fieldsValid && apiKeyValid,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.home_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        }
    )
}
