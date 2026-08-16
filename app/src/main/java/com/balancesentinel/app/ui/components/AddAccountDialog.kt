package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.balance.PresetScripts
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.parseUsageDisplayFieldLines
import androidx.compose.ui.text.font.FontFamily

@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (AccountDraft) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ProviderType.DEEPSEEK) }
    var label by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var usageScript by remember { mutableStateOf("") }
    var usageScriptEnabled by remember { mutableStateOf(true) }
    var displayFieldsText by remember { mutableStateOf("") }
    var balanceField by remember { mutableStateOf("") }
    val fields = remember(selectedProvider) { ProviderConfigs.getConfigFields(selectedProvider) }
    var values by remember(selectedProvider) {
        mutableStateOf(fields.associate { it.key to it.defaultValue.orEmpty() })
    }

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
            ProviderType.COHERE,
            ProviderType.MODEL_ARK,
            ProviderType.CUSTOM
        )
    }

    val apiKey = values["apiKey"].orEmpty()
    val fieldsValid = ProviderConfigs.validateFieldValues(selectedProvider, values)
    val apiKeyValid = ProviderConfigs.validateApiKey(selectedProvider, apiKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_account_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box {
                    OutlinedTextField(
                        value = stringResource(selectedProvider.displayNameResource()),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.account_provider_label)) },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(
                                modifier = Modifier.testTag("account_provider_selector"),
                                onClick = { expanded = true }
                            ) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    stringResource(R.string.account_provider_expand)
                                )
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
                                modifier = Modifier.testTag("account_provider_option_${provider.id}"),
                                text = { Text(stringResource(provider.displayNameResource())) },
                                onClick = {
                                    selectedProvider = provider
                                    expanded = false
                                }
                            )
                        }
                    }
                }

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

                if (selectedProvider == ProviderType.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.account_custom_script_toggle))
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
                            placeholder = { Text(stringResource(R.string.account_custom_script_placeholder)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(8.dp)
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
                        var showPresetMenu by remember { mutableStateOf(false) }
                        Box {
                            Button(onClick = { showPresetMenu = true }) {
                                Text(stringResource(R.string.account_custom_script_load_preset))
                            }
                            DropdownMenu(
                                expanded = showPresetMenu,
                                onDismissRequest = { showPresetMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.account_custom_script_preset_generic)) },
                                    onClick = {
                                        usageScript = PresetScripts.getCustomTemplate().code
                                        showPresetMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.account_custom_script_preset_newapi)) },
                                    onClick = {
                                        usageScript = PresetScripts.getNewApiTemplate().code
                                        showPresetMenu = false
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
                    onAdd(
                        AccountDraft(
                            label = label,
                            apiKey = apiKey,
                            providerType = selectedProvider,
                            extraCredentials = ProviderConfigs.valuesForStorage(
                                selectedProvider,
                                values,
                                ConfigFieldStorage.EXTRA_CREDENTIAL
                            ),
                            extraSettings = ProviderConfigs.valuesForStorage(
                                selectedProvider,
                                values,
                                ConfigFieldStorage.SETTING
                            ),
                            usageScript = if (selectedProvider == ProviderType.CUSTOM) usageScript.ifBlank { null } else null,
                            usageScriptEnabled = if (selectedProvider == ProviderType.CUSTOM) usageScriptEnabled else true,
                            authorizedScriptOrigins = emptySet(),
                            usageDisplayFields = if (selectedProvider == ProviderType.CUSTOM) {
                                parseUsageDisplayFieldLines(displayFieldsText)
                            } else emptyMap(),
                            usageBalanceField = if (selectedProvider == ProviderType.CUSTOM) {
                                balanceField.trim().ifBlank { null }
                            } else null
                        )
                    )
                },
                enabled = label.isNotBlank() && fieldsValid && apiKeyValid,
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
