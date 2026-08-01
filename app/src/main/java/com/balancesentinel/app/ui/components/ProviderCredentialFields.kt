package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.api.ConfigField
import com.balancesentinel.app.data.api.FieldType

@Composable
fun ProviderCredentialFields(
    fields: List<ConfigField>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.forEach { field ->
            val value = values[field.key].orEmpty()
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(field.key, it) },
                label = { Text(field.displayName) },
                placeholder = field.hint?.let { hint -> { Text(hint) } },
                visualTransformation = if (field.type == FieldType.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        FieldType.PASSWORD -> KeyboardType.Password
                        FieldType.URL -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = field.required && value.isBlank()
            )
            if (field.required && value.isBlank()) {
                Text(field.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
