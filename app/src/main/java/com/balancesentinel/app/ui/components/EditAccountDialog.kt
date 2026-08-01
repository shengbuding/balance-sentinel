package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo

@Composable
fun EditAccountDialog(
    account: AccountInfo,
    onDismiss: () -> Unit,
    onConfirm: (AccountDraft) -> Unit
) {
    var label by remember(account.id) { mutableStateOf(account.label) }
    val fields = remember(account.providerType) { ProviderConfigs.getConfigFields(account.providerType) }
    var values by remember(account.id) {
        mutableStateOf(fields.associate { field ->
            val value = when (field.storage) {
                ConfigFieldStorage.PRIMARY_CREDENTIAL -> account.apiKey
                ConfigFieldStorage.EXTRA_CREDENTIAL -> account.extraCredentials[field.key].orEmpty()
                ConfigFieldStorage.SETTING -> account.extraSettings[field.key].orEmpty()
            }
            field.key to value
        })
    }
    var usageScript by remember(account.id) { mutableStateOf(account.usageScript.orEmpty()) }
    var usageScriptEnabled by remember(account.id) { mutableStateOf(account.usageScriptEnabled) }
    val allRequiredPresent = fields.filter { it.required }.all { values[it.key].orEmpty().isNotBlank() }
    val apiKey = values["apiKey"].orEmpty()
    val valid = label.isNotBlank() && allRequiredPresent && ProviderConfigs.validateApiKey(account.providerType, apiKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = account.providerType.displayName,
                    onValueChange = {},
                    label = { Text("Provider") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ProviderCredentialFields(fields, values) { key, value ->
                    values = values + (key to value)
                }
                if (account.providerType == com.balancesentinel.app.data.api.ProviderType.CUSTOM) {
                    Switch(
                        checked = usageScriptEnabled,
                        onCheckedChange = { usageScriptEnabled = it }
                    )
                    OutlinedTextField(
                        value = usageScript,
                        onValueChange = { usageScript = it },
                        label = { Text("Usage script") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onConfirm(
                        AccountDraft(
                            label = label,
                            apiKey = apiKey,
                            providerType = account.providerType,
                            extraCredentials = fields
                                .filter { it.storage == ConfigFieldStorage.EXTRA_CREDENTIAL }
                                .associate { it.key to values[it.key].orEmpty() },
                            extraSettings = fields
                                .filter { it.storage == ConfigFieldStorage.SETTING }
                                .associate { it.key to values[it.key].orEmpty() },
                            usageScript = usageScript.ifBlank { null },
                            usageScriptEnabled = usageScriptEnabled,
                            authorizedScriptOrigins = account.authorizedScriptOrigins
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
