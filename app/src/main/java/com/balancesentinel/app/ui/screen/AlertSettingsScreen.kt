package com.balancesentinel.app.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.SnoozeInfo
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.ui.theme.WalletColors
import com.balancesentinel.app.widget.BalanceWidgetDataStore

/**
 * 预警设置页面 — 分账户、分币种控制余额预警和异动提醒的启用/禁用。
 *
 * 包含两个区域：
 * 1. 分账户/币种开关 — 每个账户下的每个币种有独立的余额预警和异动提醒 Switch
 * 2. 全局参数 — 所有账户共享的阈值、时间窗口、暂停时长
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { WidgetPrefs(context) }
    val apiKeyManager = remember { ApiKeyManager(context) }
    val accounts = remember { apiKeyManager.getAccounts() }

    // 从缓存中获取每个账户的币种列表
    val accountCurrencies = remember(accounts) {
        val allBalances = BalanceWidgetDataStore.getAllBalances(context)
        accounts.associate { account ->
            val currencies = allBalances
                .filter { it.accountId == account.id }
                .map { it.currency }
                .distinct()
            account.id to currencies
        }
    }

    // 全局阈值状态
    var alertThresholdInput by remember {
        mutableStateOf(
            if (prefs.alertThreshold > 0f) prefs.alertThreshold.toInt().toString() else ""
        )
    }
    var changeThresholdInput by remember {
        mutableStateOf(
            if (prefs.changeAlertThreshold > 0f) prefs.changeAlertThreshold.toInt().toString() else ""
        )
    }
    var changePeriodInput by remember {
        mutableStateOf(
            if (prefs.changeAlertPeriodMinutes > 0) prefs.changeAlertPeriodMinutes.toString() else ""
        )
    }

    // Snooze 信息
    var snoozeInfo by remember { mutableStateOf(prefs.getSnoozeInfo()) }

    // 通知栏：显示总余额
    var showTotal by remember { mutableStateOf(prefs.showTotalBalanceInNotification) }

    // 通知栏钱包排序列表（驱动 UI 重组的 key）
    var orderVersion by remember { mutableStateOf(0) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alert_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Snooze 状态横幅 ──
            if (snoozeInfo.anySnoozed) {
                SnoozeBanner(snoozeInfo, prefs, apiKeyManager) {
                    prefs.clearAllSnooze()
                    snoozeInfo = prefs.getSnoozeInfo()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_alert_snooze_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // ── 区域 0: 通知栏额外显示 ──
            SectionHeader(stringResource(R.string.alert_settings_notification_title))
            NotificationHintCard(
                showTotal = showTotal,
                totalOrderPos = remember(orderVersion) {
                    prefs.getNotificationWalletPosition(WidgetPrefs.KEY_NOTIFICATION_TOTAL, "")
                },
                totalCount = remember(orderVersion) { prefs.getNotificationWalletCount() },
                onShowTotalChange = { checked ->
                    showTotal = checked
                    prefs.showTotalBalanceInNotification = checked
                    orderVersion++
                },
                onMoveTotalUp = {
                    prefs.moveNotificationWalletUp(WidgetPrefs.KEY_NOTIFICATION_TOTAL, "")
                    orderVersion++
                },
                onMoveTotalDown = {
                    prefs.moveNotificationWalletDown(WidgetPrefs.KEY_NOTIFICATION_TOTAL, "")
                    orderVersion++
                }
            )

            // ── 区域 1: 分账户/币种开关 ──
            SectionHeader(stringResource(R.string.alert_settings_section_accounts))

            if (accounts.isEmpty()) {
                NoAccountsCard()
            } else {
                accounts.forEach { account ->
                    val currencies = accountCurrencies[account.id].orEmpty()
                    AccountAlertCard(
                        account = account,
                        currencies = currencies,
                        prefs = prefs,
                        showNotificationColumn = true,
                        orderVersion = orderVersion,
                        onMoveUp = { aid, cur ->
                            prefs.moveNotificationWalletUp(aid, cur)
                            orderVersion++
                        },
                        onMoveDown = { aid, cur ->
                            prefs.moveNotificationWalletDown(aid, cur)
                            orderVersion++
                        },
                        onToggle = {
                            orderVersion++
                            snoozeInfo = prefs.getSnoozeInfo()
                        }
                    )
                }
            }

            // ── 区域 2: 全局参数 ──
            SectionHeader(stringResource(R.string.alert_settings_section_global))

            // 余额预警阈值
            ThresholdCard(
                icon = { Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                title = stringResource(R.string.alert_settings_balance_threshold_label),
                hint = stringResource(R.string.alert_settings_threshold_hint),
                inputValue = alertThresholdInput,
                onInputChange = { alertThresholdInput = it.filter { c -> c.isDigit() } },
                currentValue = prefs.alertThreshold,
                currentLabel = stringResource(R.string.settings_alert_current, prefs.alertThreshold.toInt()),
                onApply = {
                    val num = alertThresholdInput.toFloatOrNull()
                    if (num != null && num > 0f) {
                        prefs.alertThreshold = num
                        prefs.clearAllSnooze()
                        snoozeInfo = prefs.getSnoozeInfo()
                    }
                }
            )

            // 异动阈值
            ThresholdCard(
                icon = { Icon(CustomIcons.TrendingUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                title = stringResource(R.string.alert_settings_change_threshold_label),
                hint = stringResource(R.string.alert_settings_threshold_hint),
                inputValue = changeThresholdInput,
                onInputChange = { changeThresholdInput = it.filter { c -> c.isDigit() } },
                currentValue = prefs.changeAlertThreshold,
                currentLabel = stringResource(R.string.settings_alert_current, prefs.changeAlertThreshold.toInt()),
                onApply = {
                    val num = changeThresholdInput.toFloatOrNull()
                    if (num != null && num > 0f) {
                        prefs.changeAlertThreshold = num
                        prefs.clearAllSnooze()
                        snoozeInfo = prefs.getSnoozeInfo()
                    }
                }
            )

            // 异动时间窗口
            ThresholdCard(
                icon = { Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                title = stringResource(R.string.alert_settings_change_period_label),
                hint = stringResource(R.string.alert_settings_period_hint),
                inputValue = changePeriodInput,
                onInputChange = { changePeriodInput = it.filter { c -> c.isDigit() } },
                currentValue = prefs.changeAlertPeriodMinutes.toFloat(),
                currentLabel = if (prefs.changeAlertPeriodMinutes > 0)
                    stringResource(R.string.settings_alert_snooze_duration_current, prefs.changeAlertPeriodMinutes)
                else "",
                onApply = {
                    val num = changePeriodInput.toIntOrNull()
                    if (num != null && num > 0) {
                        prefs.changeAlertPeriodMinutes = num
                    }
                }
            )

            // 暂停时长快选
            SnoozeDurationCard(
                currentMinutes = prefs.snoozeDurationMinutes,
                onSelect = { minutes ->
                    prefs.snoozeDurationMinutes = minutes
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 区域标题
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

// ═══════════════════════════════════════════════════════════
// 无账户提示
// ═══════════════════════════════════════════════════════════

@Composable
private fun NoAccountsCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                stringResource(R.string.alert_settings_no_data_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 单账户预警卡片（含币种列表）
// ═══════════════════════════════════════════════════════════

@Composable
private fun AccountAlertCard(
    account: AccountInfo,
    currencies: List<String>,
    prefs: WidgetPrefs,
    showNotificationColumn: Boolean,
    orderVersion: Int,
    onMoveUp: (String, String) -> Unit,
    onMoveDown: (String, String) -> Unit,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 账户名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.alert_settings_account_label, account.label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (currencies.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.alert_settings_no_data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                // 表头
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "币种",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (showNotificationColumn) {
                        Text(
                            stringResource(R.string.alert_settings_notification_wallet),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(52.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.alert_settings_balance_switch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp)
                    )
                    Text(
                        stringResource(R.string.alert_settings_change_switch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                currencies.forEach { currency ->
                    // 用本地状态管理，确保 Switch 点击后立即反映 UI 变化
                    var balanceOn by remember {
                        mutableStateOf(prefs.isBalanceAlertEnabled(account.id, currency))
                    }
                    var changeOn by remember {
                        mutableStateOf(prefs.isChangeAlertEnabled(account.id, currency))
                    }
                    var notifOn by remember(orderVersion) {
                        mutableStateOf(prefs.isNotificationWalletSelected(account.id, currency))
                    }
                    val pos = remember(orderVersion) {
                        prefs.getNotificationWalletPosition(account.id, currency)
                    }
                    val totalCount = remember(orderVersion) {
                        prefs.getNotificationWalletCount()
                    }
                    CurrencyAlertRow(
                        currency = currency,
                        balanceEnabled = balanceOn,
                        changeEnabled = changeOn,
                        notificationChecked = notifOn,
                        notificationOrderPos = pos,
                        canMoveUp = notifOn && pos > 0,
                        canMoveDown = notifOn && pos >= 0 && pos < totalCount - 1,
                        showNotificationCheckbox = showNotificationColumn,
                        onNotificationToggle = { checked ->
                            notifOn = checked
                            prefs.setNotificationWalletSelected(account.id, currency, checked)
                            onToggle()
                        },
                        onMoveUp = { onMoveUp(account.id, currency) },
                        onMoveDown = { onMoveDown(account.id, currency) },
                        onBalanceToggle = { enabled ->
                            balanceOn = enabled
                            prefs.setBalanceAlertEnabled(account.id, currency, enabled)
                            onToggle()
                        },
                        onChangeToggle = { enabled ->
                            changeOn = enabled
                            prefs.setChangeAlertEnabled(account.id, currency, enabled)
                            onToggle()
                        }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 单个币种的预警开关行
// ═══════════════════════════════════════════════════════════

@Composable
private fun CurrencyAlertRow(
    currency: String,
    balanceEnabled: Boolean,
    changeEnabled: Boolean,
    notificationChecked: Boolean,
    notificationOrderPos: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showNotificationCheckbox: Boolean,
    onNotificationToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onBalanceToggle: (Boolean) -> Unit,
    onChangeToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 币种名称
        Text(
            text = currency,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        // 通知栏复选框 + 排序控制（仅在勾选时显示）
        if (showNotificationCheckbox) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (notificationChecked && notificationOrderPos >= 0) {
                    // 排序位置标签
                    Text(
                        text = "#${notificationOrderPos + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    // 上移按钮
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "↑",
                            tint = if (canMoveUp) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    // 下移按钮
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "↓",
                            tint = if (canMoveDown) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Checkbox(
                    checked = notificationChecked,
                    onCheckedChange = onNotificationToggle,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // 余额预警 Switch
        Switch(
            checked = balanceEnabled,
            onCheckedChange = onBalanceToggle,
            modifier = Modifier.width(56.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // 异动提醒 Switch
        Switch(
            checked = changeEnabled,
            onCheckedChange = onChangeToggle,
            modifier = Modifier.width(56.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
// Snooze 状态横幅
// ═══════════════════════════════════════════════════════════

@Composable
private fun SnoozeBanner(
    snoozeInfo: SnoozeInfo,
    prefs: WidgetPrefs,
    apiKeyManager: ApiKeyManager,
    onClear: () -> Unit
) {
    val remainingMin = (snoozeInfo.maxRemainingMs / 60_000L).toInt().coerceAtLeast(1)
    val accountLabels = remember(snoozeInfo) {
        try {
            val accounts = apiKeyManager.getAccounts()
            snoozeInfo.snoozedAccountIds.mapNotNull { id ->
                accounts.find { it.id == id }?.label
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = WalletColors.warningBg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = WalletColors.warning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_alert_snoozed),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = WalletColors.warningText
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    stringResource(R.string.settings_alert_snoozed_remaining, remainingMin),
                    style = MaterialTheme.typography.bodySmall,
                    color = WalletColors.warningTextDim.copy(alpha = 0.8f)
                )
                if (accountLabels.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_alert_snoozed_accounts, accountLabels.joinToString(", ")),
                        style = MaterialTheme.typography.labelSmall,
                        color = WalletColors.warningTextDim.copy(alpha = 0.7f)
                    )
                }
            }
            Button(
                onClick = onClear,
                colors = ButtonDefaults.buttonColors(containerColor = WalletColors.warning),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    stringResource(R.string.settings_alert_snooze_dismiss),
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 阈值设置卡片（可复用）
// ═══════════════════════════════════════════════════════════

@Composable
private fun ThresholdCard(
    icon: @Composable () -> Unit,
    title: String,
    hint: String,
    inputValue: String,
    onInputChange: (String) -> Unit,
    currentValue: Float,
    currentLabel: String,
    onApply: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onInputChange,
                    label = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApply, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(R.string.settings_confirm))
                }
            }

            if (currentLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 暂停时长快选卡片
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeDurationCard(
    currentMinutes: Int,
    onSelect: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.alert_settings_snooze_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_alert_snooze_duration_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    30 to R.string.settings_alert_snooze_quick_30,
                    60 to R.string.settings_alert_snooze_quick_60,
                    120 to R.string.settings_alert_snooze_quick_120,
                    240 to R.string.settings_alert_snooze_quick_240
                ).forEach { (min, labelRes) ->
                    FilterChip(
                        selected = currentMinutes == min,
                        onClick = { onSelect(min) },
                        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_alert_snooze_duration_current, currentMinutes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 通知栏提示卡片
// ═══════════════════════════════════════════════════════════

@Composable
private fun NotificationHintCard(
    showTotal: Boolean,
    totalOrderPos: Int,
    totalCount: Int,
    onShowTotalChange: (Boolean) -> Unit,
    onMoveTotalUp: () -> Unit,
    onMoveTotalDown: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    stringResource(R.string.alert_settings_notification_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 总余额行：checkbox + 排序位置 + ↑↓ 按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showTotal && totalOrderPos >= 0) {
                    Text(
                        text = "#${totalOrderPos + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    IconButton(
                        onClick = onMoveTotalUp,
                        enabled = totalOrderPos > 0,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "↑",
                            tint = if (totalOrderPos > 0) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveTotalDown,
                        enabled = totalOrderPos < totalCount - 1,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "↓",
                            tint = if (totalOrderPos < totalCount - 1) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Checkbox(
                    checked = showTotal,
                    onCheckedChange = onShowTotalChange,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.alert_settings_show_total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
