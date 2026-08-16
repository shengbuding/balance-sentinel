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
import com.balancesentinel.app.data.model.formatUsageDisplayFieldLines
import com.balancesentinel.app.data.model.parseUsageDisplayFieldLines

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
    var displayFieldsText by remember(account.id, account.revision) {
        mutableStateOf(formatUsageDisplayFieldLines(account.usageDisplayFields))
    }
    var balanceField by remember(account.id, account.revision) {
        mutableStateOf(account.usageBalanceField.orEmpty())
    }
    val isCustom = account.providerType == ProviderType.CUSTOM
    val apiKey = values["apiKey"].orEmpty()
    val baseUrl = values["baseUrl"].orEmpty()
    val fieldsValid = ProviderConfigs.validateFieldValues(account.providerType, values)
    val apiKeyValid = ProviderConfigs.validateApiKey(account.providerType, apiKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = stringResource(account.providerType.displayNameResource()),
                    onValueChange = {},
                    label = { Text(stringResource(R.string.account_provider_label)) },
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
                        text = stringResource(R.string.account_api_key_invalid),
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
                            text = stringResource(R.string.account_custom_script_toggle),
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
                            label = { Text(stringResource(R.string.account_custom_script_label)) },
                            placeholder = {
                                Text(
                                    stringResource(R.string.account_custom_script_placeholder),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp),
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )

                        OutlinedTextField(
                            value = displayFieldsText,
                            onValueChange = { displayFieldsText = it },
                            label = { Text(stringResource(R.string.account_custom_display_fields_label)) },
                            supportingText = { Text(stringResource(R.string.account_custom_display_fields_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = balanceField,
                            onValueChange = { balanceField = it },
                            label = { Text(stringResource(R.string.account_custom_balance_field_label)) },
                            supportingText = { Text(stringResource(R.string.account_custom_balance_field_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        Text(
                            text = stringResource(R.string.account_custom_script_help),
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
                                Text(stringResource(R.string.account_custom_script_load_preset))
                            }
                            DropdownMenu(
                                expanded = showPresetMenu,
                                onDismissRequest = { showPresetMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.account_custom_script_preset_generic))
                                    },
                                    onClick = {
                                        showPresetMenu = false
                                        usageScript = PresetScripts.getCustomTemplate().code
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.account_custom_script_preset_newapi))
                                    },
                                    onClick = {
                                        showPresetMenu = false
                                        usageScript = PresetScripts.getNewApiTemplate().code
                                    }
                                )
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
                            authorizedScriptOrigins = account.authorizedScriptOrigins,
                            usageDisplayFields = if (isCustom) {
                                parseUsageDisplayFieldLines(displayFieldsText)
                            } else {
                                account.usageDisplayFields
                            },
                            usageBalanceField = if (isCustom) {
                                balanceField.trim().ifBlank { null }
                            } else {
                                account.usageBalanceField
                            }
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
