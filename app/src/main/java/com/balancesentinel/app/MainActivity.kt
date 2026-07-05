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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.service.BalanceRefreshService
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.screen.DataManagementScreen
import com.balancesentinel.app.ui.screen.HomeScreen
import com.balancesentinel.app.ui.screen.InsightsScreen
import com.balancesentinel.app.ui.screen.LogScreen
import com.balancesentinel.app.ui.screen.SettingsScreen
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import com.balancesentinel.app.ui.viewmodel.InsightsViewModel
import com.balancesentinel.app.ui.viewmodel.LogViewModel
import com.balancesentinel.app.util.BatteryOptimizationHelper

enum class Screen { HOME, INSIGHTS, SETTINGS, LOG, DATA_MANAGEMENT }

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        startRefreshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 在任何权限检查之前标记启动意图——即使权限弹窗延迟了 Service 启动，
        // ViewModel 的 checkMissedRefreshes() 也知道服务正在启动中，不会误判
        RefreshScheduler.markStartRequested(this)
        requestNotificationAndStartService()

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
                var currentScreen by remember {
                    mutableStateOf(
                        if (deepLinkTarget == "insights") Screen.INSIGHTS else Screen.HOME
                    )
                }
                val context = LocalContext.current

                // 首次启动电池优化引导
                var showBatteryGuide by remember { mutableStateOf(false) }
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
                                onNavigateToDataManagement = { currentScreen = Screen.DATA_MANAGEMENT }
                            )
                            Screen.LOG -> LogScreen(
                                viewModel = logViewModel,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                            Screen.DATA_MANAGEMENT -> DataManagementScreen(
                                viewModel = dataManagementViewModel,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
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
