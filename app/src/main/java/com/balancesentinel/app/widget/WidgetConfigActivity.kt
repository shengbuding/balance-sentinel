package com.balancesentinel.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme

/**
 * Widget 配置 Activity。
 * 用户添加 Widget 时弹出，选择要显示的账户和币种。
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val apiKeyManager = ApiKeyManager(this)
        val accounts = apiKeyManager.getAccounts()
        val currencies = listOf("CNY", "USD", "EUR")

        setResult(RESULT_CANCELED) // default

        setContent {
            DeepSeekBalanceTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) })
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .padding(16.dp)
                    ) {
                        // 安全默认值
                        val defaultAccountId = accounts.firstOrNull()?.id ?: ""
                        val defaultAccountLabel = accounts.firstOrNull()?.label ?: ""

                        var selectedAccountId by remember { mutableStateOf(defaultAccountId) }
                        var selectedCurrency by remember { mutableStateOf("CNY") }

                        // 账户下拉
                        var accountExpanded by remember { mutableStateOf(false) }
                        val selectedLabel = accounts.find { it.id == selectedAccountId }?.label
                            ?: defaultAccountLabel

                        Text(
                            text = stringResource(R.string.widget_config_account),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AccountDropdown(
                            expanded = accountExpanded,
                            onExpandedChange = { accountExpanded = it },
                            selectedLabel = selectedLabel,
                            accounts = accounts,
                            onAccountSelected = { account ->
                                selectedAccountId = account.id
                                accountExpanded = false
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // 币种下拉
                        var currencyExpanded by remember { mutableStateOf(false) }
                        Text(
                            text = stringResource(R.string.widget_config_currency),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CurrencyDropdown(
                            expanded = currencyExpanded,
                            onExpandedChange = { currencyExpanded = it },
                            selectedCurrency = selectedCurrency,
                            currencies = currencies,
                            onCurrencySelected = { currency ->
                                selectedCurrency = currency
                                currencyExpanded = false
                            }
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        // 按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                setResult(RESULT_CANCELED)
                                finish()
                            }) {
                                Text(stringResource(R.string.home_cancel))
                            }
                            Button(onClick = {
                                if (selectedAccountId.isNotEmpty()) {
                                    WidgetConfigStore.saveConfig(
                                        this@WidgetConfigActivity, appWidgetId,
                                        selectedAccountId, selectedCurrency
                                    )
                                    // 立即刷新 Widget
                                    val manager = AppWidgetManager.getInstance(this@WidgetConfigActivity)
                                    StaticWidgetProvider_2x1().onUpdate(
                                        this@WidgetConfigActivity, manager, intArrayOf(appWidgetId)
                                    )
                                    val resultIntent = Intent().apply {
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                    }
                                    setResult(RESULT_OK, resultIntent)
                                }
                                finish()
                            }) {
                                Text(stringResource(R.string.home_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun AccountDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedLabel: String,
    accounts: List<com.balancesentinel.app.data.model.AccountInfo>,
    onAccountSelected: (com.balancesentinel.app.data.model.AccountInfo) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedLabel.ifEmpty { "—" },
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.label.ifEmpty { account.id }) },
                    onClick = { onAccountSelected(account) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun CurrencyDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedCurrency: String,
    currencies: List<String>,
    onCurrencySelected: (String) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedCurrency,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            currencies.forEach { currency ->
                DropdownMenuItem(
                    text = { Text(currency) },
                    onClick = { onCurrencySelected(currency) }
                )
            }
        }
    }
}
