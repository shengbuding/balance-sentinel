package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountDraft

@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (AccountDraft) -> Unit
) {
    var provider by remember { mutableStateOf(ProviderType.DEEPSEEK) }
    var label by remember { mutableStateOf("") }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }
    var expanded by remember { mutableStateOf(false) }
    val fields = ProviderConfigs.getConfigFields(provider)
    val allRequiredPresent = fields.filter { it.required }.all { values[it.key].orEmpty().isNotBlank() }
    val apiKey = values["apiKey"].orEmpty()
    val valid = label.isNotBlank() && allRequiredPresent && ProviderConfigs.validateApiKey(provider, apiKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = provider.displayName,
                    onValueChange = {},
                    label = { Text("Provider") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Provider")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ProviderType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                provider = type
                                values = ProviderConfigs.getConfigFields(type)
                                    .mapNotNull { field -> field.defaultValue?.let { field.key to it } }
                                    .toMap()
                                expanded = false
                            }
                        )
                    }
                }
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
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onAdd(
                        AccountDraft(
                            label = label,
                            apiKey = apiKey,
                            providerType = provider,
                            extraCredentials = fields
                                .filter { it.storage == ConfigFieldStorage.EXTRA_CREDENTIAL }
                                .associate { it.key to values[it.key].orEmpty() },
                            extraSettings = fields
                                .filter { it.storage == ConfigFieldStorage.SETTING }
                                .associate { it.key to values[it.key].orEmpty() },
                            usageScript = null,
                            usageScriptEnabled = true,
                            authorizedScriptOrigins = emptySet()
                        )
                    )
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
