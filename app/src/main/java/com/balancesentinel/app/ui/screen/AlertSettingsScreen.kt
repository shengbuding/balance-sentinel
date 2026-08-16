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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.balancesentinel.app.R
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.SnoozeInfo
import com.balancesentinel.app.data.repository.notificationWalletDisplayPosition
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.ui.theme.WalletColors
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import com.balancesentinel.app.ui.viewmodel.CapabilityViewModel
import com.balancesentinel.app.ui.components.NotificationCapabilityBanner
import kotlinx.coroutines.delay

internal enum class AlertSettingsContentMode { LOADING, READY }

internal fun alertSettingsContentMode(settingsLoading: Boolean): AlertSettingsContentMode =
    if (settingsLoading) AlertSettingsContentMode.LOADING else AlertSettingsContentMode.READY

internal fun sanitizeDecimalInput(value: String): String {
    var dotSeen = false
    return buildString {
        value.forEach { character ->
            when {
                character.isDigit() -> append(character)
                character == '.' && !dotSeen -> {
                    dotSeen = true
                    append(character)
                }
            }
        }
    }
}

private fun formatThreshold(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

private val AlertNotificationColumnWidth = 56.dp
private val AlertSwitchColumnWidth = 64.dp

/**
 * 预警设置页面 — 分账户、分币种控制余额预警和异动提醒的启用/禁用。
 *
 * 包含两个区域：
 * 1. 分账户/币种开关 — 每个账户下的每个币种有独立的余额预警和异动提醒 Switch
 * 2. 全局参数 — 所有账户共享的阈值、时间窗口、暂停时长
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    capabilityViewModel: CapabilityViewModel? = null
) {
    val context = LocalContext.current
    val resolvedCapabilityViewModel = capabilityViewModel ?: viewModel<CapabilityViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accountLoadState = uiState.accountLoadState
    val accounts = (accountLoadState as? AccountLoadState.Ready)?.accounts.orEmpty()

    // 最新余额、显式预警设置和通知选择共同组成可配置币种目录。
    val accountCurrencies = remember(
        accounts,
        uiState.accountBalances,
        uiState.accountAlertSettings,
        uiState.notificationSelections
    ) {
        accounts.associate { account ->
            val currencies = buildSet {
                addAll(uiState.accountBalances[account.id]?.balanceInfos.orEmpty().map { it.currency })
                addAll(uiState.accountAlertSettings.filter { it.accountId == account.id }.map { it.currency })
                addAll(uiState.notificationSelections.filter { it.accountId == account.id }.map { it.currency })
            }.sorted()
            account.id to currencies
        }
    }

    // 全局阈值状态
    var alertThresholdInput by remember(uiState.alertThreshold) {
        mutableStateOf(
            if (uiState.alertThreshold > 0f) formatThreshold(uiState.alertThreshold) else ""
        )
    }
    var changeThresholdInput by remember(uiState.changeAlertThreshold) {
        mutableStateOf(
            if (uiState.changeAlertThreshold > 0f) formatThreshold(uiState.changeAlertThreshold) else ""
        )
    }
    var changePeriodInput by remember(uiState.changeAlertPeriodMinutes) {
        mutableStateOf(
            if (uiState.changeAlertPeriodMinutes > 0) uiState.changeAlertPeriodMinutes.toString() else ""
        )
    }

    // Snooze 信息
    val snoozeInfo = uiState.snoozeInfo

    LaunchedEffect(snoozeInfo.anySnoozed) {
        while (snoozeInfo.anySnoozed) {
            delay(30_000L)
            viewModel.refreshSnoozeInfo()
        }
    }

    // 通知栏：显示总余额
    val showTotal = uiState.showTotalBalanceInNotification

    // 通知栏钱包排序列表（驱动 UI 重组的 key）
    var orderVersion by remember { mutableStateOf(0) }

    // 响应式版本号：用于在 prefs 写入后触发 UI 重组
    var globalApplyVersion by remember { mutableStateOf(0) }

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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
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
            NotificationCapabilityBanner(resolvedCapabilityViewModel)
            if (alertSettingsContentMode(uiState.settingsLoading) == AlertSettingsContentMode.LOADING) {
                SettingsLoadingCard()
            } else {
                if (snoozeInfo.anySnoozed) {
                    SnoozeBanner(snoozeInfo, accounts) {
                        viewModel.clearAllSnooze()
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_alert_snooze_cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // ── 默认预警：先配置行为，再配置账户覆盖和通知内容 ──
                SectionHeader(stringResource(R.string.alert_settings_section_global))

                DefaultAlertSwitchCard(
                    balanceEnabled = uiState.alertEnabled,
                    changeEnabled = uiState.changeAlertEnabled,
                    onBalanceChange = viewModel::setAlertEnabled,
                    onChangeChange = viewModel::setChangeAlertEnabled
                )

                // 派生响应式标签（globalApplyVersion 变化时重新计算）
                val alertCurrentLabel = key(globalApplyVersion) {
                    stringResource(R.string.settings_alert_current_decimal, formatThreshold(uiState.alertThreshold))
                }
                val changeCurrentLabel = key(globalApplyVersion) {
                    stringResource(R.string.settings_alert_current_decimal, formatThreshold(uiState.changeAlertThreshold))
                }
                val periodCurrentLabel = key(globalApplyVersion) {
                    if (uiState.changeAlertPeriodMinutes > 0)
                        stringResource(R.string.settings_alert_period_current, uiState.changeAlertPeriodMinutes)
                    else ""
                }
                val snoozeCurrentMinutes = key(globalApplyVersion) { uiState.snoozeDurationMinutes }

                // 余额预警阈值
                ThresholdCard(
                    icon = { Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    title = stringResource(R.string.alert_settings_balance_threshold_label),
                    hint = stringResource(R.string.alert_settings_threshold_hint),
                    inputValue = alertThresholdInput,
                    onInputChange = { alertThresholdInput = sanitizeDecimalInput(it) },
                    currentValue = uiState.alertThreshold,
                    currentLabel = alertCurrentLabel,
                    onApply = {
                        val num = alertThresholdInput.toFloatOrNull()
                        if (num != null && num > 0f) {
                            viewModel.setAlertThreshold(num)
                            globalApplyVersion++
                        }
                    }
                )

                // 异动阈值
                ThresholdCard(
                    icon = { Icon(CustomIcons.TrendingUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    title = stringResource(R.string.alert_settings_change_threshold_label),
                    hint = stringResource(R.string.alert_settings_threshold_hint),
                    inputValue = changeThresholdInput,
                    onInputChange = { changeThresholdInput = sanitizeDecimalInput(it) },
                    currentValue = uiState.changeAlertThreshold,
                    currentLabel = changeCurrentLabel,
                    onApply = {
                        val num = changeThresholdInput.toFloatOrNull()
                        if (num != null && num > 0f) {
                            viewModel.setChangeAlertThreshold(num)
                            globalApplyVersion++
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
                    currentValue = uiState.changeAlertPeriodMinutes.toFloat(),
                    currentLabel = periodCurrentLabel,
                    onApply = {
                        val num = changePeriodInput.toIntOrNull()
                        if (num != null && num > 0) {
                            viewModel.setChangeAlertPeriodMinutes(num)
                            globalApplyVersion++
                        }
                    }
                )

                // 暂停时长快选
                SnoozeDurationCard(
                    currentMinutes = snoozeCurrentMinutes,
                    onSelect = { minutes ->
                        viewModel.setSnoozeDurationMinutes(minutes)
                        globalApplyVersion++
                    }
                )

                SectionHeader(stringResource(R.string.alert_settings_section_accounts))
                when (accountLoadState) {
                    AccountLoadState.Loading -> AccountDataStateCard(
                        message = stringResource(R.string.account_data_loading),
                        loading = true
                    )
                    is AccountLoadState.Corrupt -> AccountDataStateCard(
                        message = stringResource(R.string.account_data_corrupt),
                        loading = false
                    )
                    is AccountLoadState.Ready -> {
                        if (accounts.isEmpty()) {
                            NoAccountsCard()
                        } else {
                            accounts.forEach { account ->
                                AccountAlertCard(
                                    viewModel = viewModel,
                                    account = account,
                                    currencies = accountCurrencies[account.id].orEmpty(),
                                    settings = uiState.accountAlertSettings,
                                    notifications = uiState.notificationSelections,
                                    defaultBalanceEnabled = uiState.alertEnabled,
                                    defaultChangeEnabled = uiState.changeAlertEnabled,
                                    showTotal = showTotal,
                                    totalDisplayOrder = uiState.notificationTotalDisplayOrder,
                                    showNotificationColumn = true,
                                    orderVersion = orderVersion,
                                    onMoveUp = { aid, cur ->
                                        viewModel.moveNotificationWallet(aid, cur, -1)
                                        orderVersion++
                                    },
                                    onMoveDown = { aid, cur ->
                                        viewModel.moveNotificationWallet(aid, cur, 1)
                                        orderVersion++
                                    },
                                    onToggle = { orderVersion++ }
                                )
                            }
                        }
                    }
                }

                SectionHeader(stringResource(R.string.alert_settings_notification_title))
                NotificationHintCard(
                    showTotal = showTotal,
                    totalOrderPos = if (showTotal) {
                        uiState.notificationTotalDisplayOrder
                            .coerceIn(0, uiState.notificationSelections.size)
                    } else -1,
                    totalCount = uiState.notificationSelections.size + if (showTotal) 1 else 0,
                    onShowTotalChange = { checked ->
                        viewModel.setShowTotalBalanceInNotification(checked)
                        orderVersion++
                    },
                    onMoveTotalUp = {
                        viewModel.moveNotificationTotal(-1)
                        orderVersion++
                    },
                    onMoveTotalDown = {
                        viewModel.moveNotificationTotal(1)
                        orderVersion++
                    }
                )
                }
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

@Composable
private fun DefaultAlertSwitchCard(
    balanceEnabled: Boolean,
    changeEnabled: Boolean,
    onBalanceChange: (Boolean) -> Unit,
    onChangeChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.alert_settings_default_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            AlertSwitchRow(
                label = stringResource(R.string.alert_settings_balance_switch),
                checked = balanceEnabled,
                onCheckedChange = onBalanceChange
            )
            AlertSwitchRow(
                label = stringResource(R.string.alert_settings_change_switch),
                checked = changeEnabled,
                onCheckedChange = onChangeChange
            )
        }
    }
}

@Composable
private fun AlertSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AccountDataStateCard(message: String, loading: Boolean) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (loading) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 无账户提示
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsLoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("settings_loading"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                stringResource(R.string.account_data_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
    viewModel: HomeViewModel,
    account: AccountInfo,
    currencies: List<String>,
    settings: List<AccountAlertSettingEntity>,
    notifications: List<NotificationWalletSelectionEntity>,
    defaultBalanceEnabled: Boolean,
    defaultChangeEnabled: Boolean,
    showTotal: Boolean,
    totalDisplayOrder: Int,
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

                // 表头和数据行必须共享固定列轨道，否则勾选通知排序后
                // 额外的按钮会把余额/异动开关整体推移，造成视觉错位。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.label_currency),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    if (showNotificationColumn) {
                        Text(
                            stringResource(R.string.alert_settings_notification_wallet),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(AlertNotificationColumnWidth)
                        )
                    }
                    Text(
                        stringResource(R.string.alert_settings_balance_switch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(AlertSwitchColumnWidth)
                    )
                    Text(
                        stringResource(R.string.alert_settings_change_switch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(AlertSwitchColumnWidth)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                currencies.forEach { currency ->
                    // 用本地状态管理，确保 Switch 点击后立即反映 UI 变化
                    val configured = settings.firstOrNull {
                        it.accountId == account.id && it.currency == currency
                    }
                    var balanceOn by remember(configured, defaultBalanceEnabled, account.id, currency) {
                        mutableStateOf(configured?.balanceAlertEnabled ?: defaultBalanceEnabled)
                    }
                    var changeOn by remember(configured, defaultChangeEnabled, account.id, currency) {
                        mutableStateOf(configured?.changeAlertEnabled ?: defaultChangeEnabled)
                    }
                    val selectedIndex = notifications.indexOfFirst {
                        it.accountId == account.id && it.currency == currency
                    }
                    var notifOn by remember(orderVersion, selectedIndex) {
                        mutableStateOf(selectedIndex >= 0)
                    }
                    val pos = notificationWalletDisplayPosition(
                        selectionIndex = selectedIndex,
                        totalDisplayOrder = totalDisplayOrder.coerceIn(0, notifications.size),
                        showTotal = showTotal
                    )
                    val totalCount = notifications.size + if (showTotal) 1 else 0
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
                            viewModel.setNotificationWalletSelected(account.id, currency, checked)
                            onToggle()
                        },
                        onMoveUp = { onMoveUp(account.id, currency) },
                        onMoveDown = { onMoveDown(account.id, currency) },
                        onBalanceToggle = { enabled ->
                            balanceOn = enabled
                            viewModel.setAccountAlertEnabled(
                                account.id,
                                currency,
                                balanceEnabled = enabled
                            )
                            onToggle()
                        },
                        onChangeToggle = { enabled ->
                            changeOn = enabled
                            viewModel.setAccountAlertEnabled(
                                account.id,
                                currency,
                                changeEnabled = enabled
                            )
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
    val enabledState = stringResource(R.string.accessibility_enabled)
    val disabledState = stringResource(R.string.accessibility_disabled)
    val moveUpDescription = stringResource(R.string.accessibility_move_up)
    val moveDownDescription = stringResource(R.string.accessibility_move_down)
    val notificationDescription = stringResource(R.string.alert_settings_notification_wallet)
    val balanceDescription = stringResource(R.string.alert_settings_balance_switch)
    val changeDescription = stringResource(R.string.alert_settings_change_switch)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 币种名称
            Text(
                text = currency,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            // 通知栏复选框使用固定单元；排序按钮另起一行，避免改变列宽。
            if (showNotificationCheckbox) {
                Box(
                    modifier = Modifier.width(AlertNotificationColumnWidth),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = notificationChecked,
                        onCheckedChange = onNotificationToggle,
                        modifier = Modifier.semantics {
                            role = Role.Checkbox
                            contentDescription = "$currency $notificationDescription"
                            stateDescription = if (notificationChecked) enabledState else disabledState
                        }
                    )
                }
            }

            Box(
                modifier = Modifier.width(AlertSwitchColumnWidth),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = balanceEnabled,
                    onCheckedChange = onBalanceToggle,
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        contentDescription = "$currency $balanceDescription"
                        stateDescription = if (balanceEnabled) enabledState else disabledState
                    }
                )
            }

            Box(
                modifier = Modifier.width(AlertSwitchColumnWidth),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = changeEnabled,
                    onCheckedChange = onChangeToggle,
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        contentDescription = "$currency $changeDescription"
                        stateDescription = if (changeEnabled) enabledState else disabledState
                    }
                )
            }
        }

        if (showNotificationCheckbox && notificationChecked && notificationOrderPos >= 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${notificationOrderPos + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = moveUpDescription,
                        tint = if (canMoveUp) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = moveDownDescription,
                        tint = if (canMoveDown) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Snooze 状态横幅
// ═══════════════════════════════════════════════════════════

@Composable
private fun SnoozeBanner(
    snoozeInfo: SnoozeInfo,
    accounts: List<AccountInfo>,
    onClear: () -> Unit
) {
    val remainingMin = (snoozeInfo.maxRemainingMs / 60_000L).toInt().coerceAtLeast(1)
    val accountLabels = remember(snoozeInfo, accounts) {
        snoozeInfo.snoozedAccountIds.mapNotNull { id ->
            accounts.find { it.id == id }?.label
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
    val enabledState = stringResource(R.string.accessibility_enabled)
    val disabledState = stringResource(R.string.accessibility_disabled)
    val moveUpDescription = stringResource(R.string.accessibility_move_up)
    val moveDownDescription = stringResource(R.string.accessibility_move_down)
    val totalDescription = stringResource(R.string.alert_settings_show_total)
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
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = moveUpDescription,
                            tint = if (totalOrderPos > 0) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveTotalDown,
                        enabled = totalOrderPos < totalCount - 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = moveDownDescription,
                            tint = if (totalOrderPos < totalCount - 1) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Checkbox(
                    checked = showTotal,
                    onCheckedChange = onShowTotalChange,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Checkbox
                            contentDescription = totalDescription
                            stateDescription = if (showTotal) enabledState else disabledState
                        }
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
