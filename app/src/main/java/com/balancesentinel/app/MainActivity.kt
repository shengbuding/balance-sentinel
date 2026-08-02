package com.balancesentinel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.balancesentinel.app.data.console.DebugLogger
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.service.BalanceRefreshService
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.screen.AlertSettingsScreen
import com.balancesentinel.app.ui.screen.DataManagementScreen
import com.balancesentinel.app.ui.screen.HomeScreen
import com.balancesentinel.app.ui.screen.InsightsScreen
import com.balancesentinel.app.ui.screen.LogScreen
import com.balancesentinel.app.ui.screen.OnboardingScreen
import com.balancesentinel.app.ui.screen.SettingsScreen
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import com.balancesentinel.app.ui.viewmodel.InsightsViewModel
import com.balancesentinel.app.ui.viewmodel.LogViewModel
import com.balancesentinel.app.util.BatteryOptimizationHelper
import com.balancesentinel.app.util.OnboardingHelper

enum class Screen {
    ONBOARDING, HOME, INSIGHTS, SETTINGS, LOG, DATA_MANAGEMENT, ALERT_SETTINGS,
    CONSOLE_SELECT, CONSOLE, ADD_PLATFORM
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        startRefreshService()
    }

    // WebView Activity 启动器
    private val webViewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val cookiesJson = data?.getStringExtra("cookies") ?: "{}"
            val localStorageJson = data?.getStringExtra("local_storage") ?: "{}"
            val instanceId = data?.getStringExtra("instanceId") ?: ""

            // 解析 cookies 和 localStorage
            val cookies = try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(cookiesJson)
            } catch (e: Exception) {
                emptyMap()
            }

            val localStorage = try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(localStorageJson)
            } catch (e: Exception) {
                emptyMap()
            }

            // 保存 session
            if (instanceId.isNotBlank()) {
                val store = com.balancesentinel.app.data.console.store.ConsoleStore(this)
                val session = com.balancesentinel.app.data.console.store.ConsoleSession(
                    cookies = cookies,
                    localStorage = localStorage
                )
                store.saveSession(instanceId, session)
                DebugLogger.log("[MainActivity] Saved session for instance: $instanceId, cookies: ${cookies.size}, localStorage: ${localStorage.size}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashLogger.breadcrumb("MainActivity", "onCreate started")

        // 在任何权限检查之前标记启动意图——即使权限弹窗延迟了 Service 启动，
        // ViewModel 的 checkMissedRefreshes() 也知道服务正在启动中，不会误判
        RefreshScheduler.markStartRequested(this)
        requestNotificationAndStartService()
        CrashLogger.breadcrumb("MainActivity", "onCreate complete")

        // Deep-link 目标（从通知的 "查看详情" 按钮进入）
        val deepLinkTarget = intent?.getStringExtra("deep_link_target")
        val deepLinkAccountId = intent?.getStringExtra("deep_link_account_id")
        val deepLinkCurrency = intent?.getStringExtra("deep_link_currency")

        setContent {
            DeepSeekBalanceTheme {
                val viewModel: HomeViewModel = viewModel()
                val insightsViewModel: InsightsViewModel = viewModel()
                val logViewModel: LogViewModel = viewModel()
                val dataManagementViewModel: DataManagementViewModel = viewModel()
                val context = LocalContext.current
                var selectedPlatform by remember { mutableStateOf<com.balancesentinel.app.ui.console.ConsolePlatform?>(null) }
                var currentScreen by remember {
                    mutableStateOf(
                        when {
                            OnboardingHelper.shouldShow(context) -> Screen.ONBOARDING
                            deepLinkTarget == "insights" -> Screen.INSIGHTS
                            else -> Screen.HOME
                        }
                    )
                }

                // 控制台刷新触发器
                var consoleRefreshTrigger by remember { mutableStateOf(0) }

                // 首次启动电池优化引导
                var showBatteryGuide by remember { mutableStateOf(false) }

                // Update checker auto-check state (once per session)
                var updateCheckPerformed by remember { mutableStateOf(false) }
                var showAutoUpdateDialog by remember { mutableStateOf(false) }
                var autoUpdateRelease by remember { mutableStateOf<com.balancesentinel.app.data.model.GitHubRelease?>(null) }
                var autoUpdateCurrentVersion by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    if (BatteryOptimizationHelper.shouldShowGuide(context)) {
                        showBatteryGuide = true
                    }
                }
                if (showBatteryGuide) {
                    AlertDialog(
                        onDismissRequest = {
                            showBatteryGuide = false
                            BatteryOptimizationHelper.recordDismiss(context)
                        },
                        title = { Text(stringResource(R.string.settings_battery_guide_title)) },
                        text = { Text(stringResource(R.string.settings_battery_guide_desc)) },
                        confirmButton = {
                            TextButton(onClick = {
                                BatteryOptimizationHelper.markGuideShown(context)
                                BatteryOptimizationHelper.openBatterySettings(context)
                                showBatteryGuide = false
                            }) {
                                Text(stringResource(R.string.settings_close_battery_opt))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showBatteryGuide = false
                                BatteryOptimizationHelper.recordDismiss(context)
                            }) {
                                Text(stringResource(R.string.settings_later))
                            }
                        }
                    )
                }

                // Auto update dialog
                if (showAutoUpdateDialog && autoUpdateRelease != null) {
                    com.balancesentinel.app.ui.screen.UpdateDialog(
                        release = autoUpdateRelease!!,
                        currentVersion = autoUpdateCurrentVersion,
                        onDismiss = { showAutoUpdateDialog = false },
                        onSkipVersion = {
                            com.balancesentinel.app.data.update.UpdatePrefs(context)
                                .skippedVersion = autoUpdateRelease!!.tagName
                            showAutoUpdateDialog = false
                        },
                        onRemindLater = {
                            showAutoUpdateDialog = false
                        }
                    )
                }

                // 每次切换页面时重新加载数据
                LaunchedEffect(currentScreen) {
                    when (currentScreen) {
                        Screen.HOME -> viewModel.loadCachedBalances()
                        Screen.SETTINGS -> viewModel.loadStatusSummary()
                        Screen.INSIGHTS -> insightsViewModel.loadData()
                        Screen.LOG -> logViewModel.loadLogs()
                        Screen.DATA_MANAGEMENT -> dataManagementViewModel.loadStats()
                        else -> {}
                    }
                }

                // Auto-check for updates when navigating to Settings
                LaunchedEffect(currentScreen) {
                    if (currentScreen == Screen.SETTINGS && !updateCheckPerformed) {
                        updateCheckPerformed = true
                        val prefs = com.balancesentinel.app.data.update.UpdatePrefs(context)
                        if (prefs.shouldAutoCheckToday()) {
                            val checker = com.balancesentinel.app.data.update.UpdateChecker()
                            val result = withContext(Dispatchers.IO) {
                                checker.checkForUpdate(context)
                            }
                            when (result) {
                                is com.balancesentinel.app.data.update.UpdateResult.UpdateAvailable -> {
                                    if (!prefs.shouldSkipVersion(result.release.tagName)) {
                                        autoUpdateRelease = result.release
                                        autoUpdateCurrentVersion = result.currentVersion
                                        showAutoUpdateDialog = true
                                        prefs.markPromptedToday()
                                    }
                                }
                                else -> { /* silent skip */ }
                            }
                        }
                    }
                }

                if (currentScreen == Screen.ONBOARDING) {
                    OnboardingScreen(
                        onComplete = { currentScreen = Screen.HOME }
                    )
                } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentScreen == Screen.HOME,
                                onClick = { currentScreen = Screen.HOME },
                                icon = { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.home_title)) },
                                label = { Text(stringResource(R.string.home_title)) }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.INSIGHTS,
                                onClick = { currentScreen = Screen.INSIGHTS },
                                icon = { Icon(CustomIcons.TrendingUp, contentDescription = stringResource(R.string.insights_title)) },
                                label = { Text(stringResource(R.string.insights_title)) }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.CONSOLE_SELECT ||
                                        currentScreen == Screen.CONSOLE,
                                onClick = { currentScreen = Screen.CONSOLE_SELECT },
                                icon = { Icon(CustomIcons.Analytics, contentDescription = "控制台") },
                                label = { Text("控制台") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.SETTINGS,
                                onClick = { currentScreen = Screen.SETTINGS },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title)) },
                                label = { Text(stringResource(R.string.settings_title)) }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        when (currentScreen) {
                            Screen.HOME -> HomeScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = { currentScreen = Screen.SETTINGS }
                            )
                            Screen.INSIGHTS -> InsightsScreen(
                                viewModel = insightsViewModel
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = Screen.HOME },
                                onNavigateToLog = { currentScreen = Screen.LOG },
                                onNavigateToDataManagement = { currentScreen = Screen.DATA_MANAGEMENT },
                                onNavigateToAlertSettings = { currentScreen = Screen.ALERT_SETTINGS }
                            )
                            Screen.LOG -> LogScreen(
                                viewModel = logViewModel,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                            Screen.DATA_MANAGEMENT -> DataManagementScreen(
                                viewModel = dataManagementViewModel,
                                onBack = { currentScreen = Screen.SETTINGS },
                                onConfigImported = viewModel::loadCachedBalances
                            )
                            Screen.ALERT_SETTINGS -> AlertSettingsScreen(
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                            Screen.CONSOLE_SELECT -> com.balancesentinel.app.ui.console.ConsoleSelectScreen(
                                onSelectPlatform = { platform ->
                                    selectedPlatform = platform
                                    currentScreen = Screen.CONSOLE
                                },
                                onAddPlatform = {
                                    currentScreen = Screen.ADD_PLATFORM
                                },
                                refreshTrigger = consoleRefreshTrigger
                            )
                            Screen.ADD_PLATFORM -> {
                                val store = com.balancesentinel.app.data.console.store.ConsoleStore(this@MainActivity)
                                val addedPlatformIds = store.getPlatforms().map { it.id }
                                com.balancesentinel.app.ui.console.AddPlatformScreen(
                                    addedPlatformIds = addedPlatformIds,
                                    onAddPreset = { platform ->
                                        store.addPlatform(platform)
                                        selectedPlatform = platform
                                        currentScreen = Screen.CONSOLE
                                    },
                                    onAddCustom = { platform ->
                                        store.addPlatform(platform)
                                        selectedPlatform = platform
                                        currentScreen = Screen.CONSOLE
                                    },
                                    onBack = {
                                        currentScreen = Screen.CONSOLE_SELECT
                                    }
                                )
                            }
                            Screen.CONSOLE -> {
                                val platform = selectedPlatform

                                if (platform != null) {
                                    // 使用统一的 ViewModel
                                    val consoleViewModel = androidx.lifecycle.viewmodel.compose.viewModel<com.balancesentinel.app.ui.viewmodel.ConsoleViewModel>(
                                        key = "console_${platform.id}",
                                        factory = com.balancesentinel.app.ui.viewmodel.ConsoleViewModel.Factory(
                                            application = application,
                                            platform = platform
                                        )
                                    )

                                    val uiState = consoleViewModel.uiState.collectAsStateWithLifecycle().value

                                    com.balancesentinel.app.ui.console.ConsoleScreen(
                                        platform = platform,
                                        uiState = uiState,
                                        onLoginSuccess = { cookies, localStorage, email ->
                                            consoleViewModel.onLoginSuccess(cookies, localStorage, email)
                                        },
                                        onLogout = {
                                            consoleViewModel.logout {
                                                consoleRefreshTrigger++
                                                currentScreen = Screen.CONSOLE_SELECT
                                            }
                                        },
                                        onBack = {
                                            currentScreen = Screen.CONSOLE_SELECT
                                        }
                                    )
                                } else {
                                    // 如果没有选中的平台，返回选择页面
                                    currentScreen = Screen.CONSOLE_SELECT
                                }
                            }
                            else -> {}
                        }
                    }
                }
                }
            }
        }
    }

    private fun requestNotificationAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startRefreshService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startRefreshService()
        }
    }

    private fun startRefreshService() {
        RefreshScheduler.markStartRequested(this)
        try {
            val intent = Intent(this, BalanceRefreshService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            com.balancesentinel.app.data.util.Logger.e("MainActivity", "Failed to start refresh service", e)
        }
    }
}
