package com.balancesentinel.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ConfigField
import com.balancesentinel.app.data.api.FieldType
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.ui.CustomIcons

@Composable
fun ProviderCredentialFields(
    fields: List<ConfigField>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit
) {
    var visiblePasswords by remember(fields) { mutableStateOf(emptySet<String>()) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        fields.forEach { field ->
            val value = values[field.key].orEmpty()
            val isPassword = field.type == FieldType.PASSWORD
            val passwordVisible = field.key in visiblePasswords
            val invalid = value.isNotBlank() && !ProviderConfigs.validateFieldValue(field, value)

            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(field.key, it) },
                label = { Text(stringResource(field.displayNameRes)) },
                placeholder = field.hintRes?.let { hintRes ->
                    { Text(stringResource(hintRes)) }
                },
                visualTransformation = if (isPassword && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                leadingIcon = if (isPassword) {
                    {
                        Box(
                            modifier = Modifier
                                .clickable {
                                    val clipText = clipboardManager.getText()?.text.orEmpty().trim()
                                    if (clipText.isNotBlank()) onValueChange(field.key, clipText)
                                }
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.add_account_paste_key),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    null
                },
                trailingIcon = if (isPassword) {
                    {
                        IconButton(
                            onClick = {
                                visiblePasswords = if (passwordVisible) {
                                    visiblePasswords - field.key
                                } else {
                                    visiblePasswords + field.key
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    CustomIcons.VisibilityOff
                                } else {
                                    CustomIcons.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    stringResource(R.string.add_account_hide_key)
                                } else {
                                    stringResource(R.string.add_account_show_key)
                                }
                            )
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        FieldType.PASSWORD -> KeyboardType.Password
                        FieldType.URL -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    }
                ),
                singleLine = field.type != FieldType.SELECT,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_field_${field.key}"),
                shape = RoundedCornerShape(8.dp),
                isError = invalid,
                supportingText = if (invalid && field.type == FieldType.URL) {
                    { Text(stringResource(R.string.account_field_url_invalid)) }
                } else {
                    null
                }
            )
        }
    }
}
