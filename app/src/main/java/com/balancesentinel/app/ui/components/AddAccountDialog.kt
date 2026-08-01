package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountDraft

@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (AccountDraft) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ProviderType.DEEPSEEK) }
    var label by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
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
                            usageScript = null,
                            usageScriptEnabled = true,
                            authorizedScriptOrigins = emptySet()
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
