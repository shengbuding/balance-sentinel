package com.balancesentinel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.update.UpdateChecker
import com.balancesentinel.app.data.update.UpdatePrefs
import com.balancesentinel.app.data.update.UpdateResult
import com.balancesentinel.app.service.BalanceRefreshService
import com.balancesentinel.app.ui.navigation.AppRoute
import com.balancesentinel.app.ui.navigation.WalletNavHost
import com.balancesentinel.app.ui.screen.UpdateDialog
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.util.BatteryOptimizationHelper
import com.balancesentinel.app.util.OnboardingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRefreshService() }

    private val webViewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val cookiesJson = data?.getStringExtra("cookies") ?: "{}"
            val localStorageJson = data?.getStringExtra("local_storage") ?: "{}"
            val instanceId = data?.getStringExtra("instanceId") ?: ""
            val cookies = try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(cookiesJson)
            } catch (_: Exception) { emptyMap() }
            val localStorage = try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(localStorageJson)
            } catch (_: Exception) { emptyMap() }
            if (instanceId.isNotBlank()) {
                val store = com.balancesentinel.app.data.console.store.ConsoleStore(this)
                store.saveSession(
                    instanceId,
                    com.balancesentinel.app.data.console.store.ConsoleSession(cookies, localStorage)
                )
                DebugLogger.log("[MainActivity] Saved session for instance: $instanceId, cookies: ${cookies.size}, localStorage: ${localStorage.size}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashLogger.breadcrumb("MainActivity", "onCreate started")
        RefreshScheduler.markStartRequested(this)
        requestNotificationAndStartService()

        val deepLinkTarget = intent?.getStringExtra(AppRoute.LEGACY_TARGET_EXTRA)
        val deepLinkAccountId = intent?.getStringExtra(AppRoute.LEGACY_ACCOUNT_EXTRA)
        val deepLinkCurrency = intent?.getStringExtra(AppRoute.LEGACY_CURRENCY_EXTRA)
        val uriSegments = intent?.data?.takeIf {
            it.scheme.equals(AppRoute.SCHEME, ignoreCase = true) &&
                it.host.equals(AppRoute.INSIGHTS_HOST, ignoreCase = true)
        }?.pathSegments.orEmpty()
        val uriAccountId = uriSegments.getOrNull(0)
        val uriCurrency = uriSegments.getOrNull(1)
        val requestedStart = when {
            OnboardingHelper.shouldShow(this) -> AppRoute.Onboarding.route
            !uriAccountId.isNullOrBlank() && !uriCurrency.isNullOrBlank() ->
                AppRoute.Insights(Uri.decode(uriAccountId), Uri.decode(uriCurrency)).route
            deepLinkTarget.equals("insights", ignoreCase = true) &&
                !deepLinkAccountId.isNullOrBlank() && !deepLinkCurrency.isNullOrBlank() ->
                AppRoute.Insights(deepLinkAccountId, deepLinkCurrency).route
            deepLinkTarget != null -> AppRoute.InvalidDeepLink("legacy_deep_link").route
            else -> AppRoute.Home.route
        }

        setContent {
            DeepSeekBalanceTheme {
                val context = LocalContext.current
                var currentRoute by remember { mutableStateOf(requestedStart) }
                var showBatteryGuide by remember { mutableStateOf(false) }
                var updateCheckPerformed by remember { mutableStateOf(false) }
                var showAutoUpdateDialog by remember { mutableStateOf(false) }
                var autoUpdateRelease by remember { mutableStateOf<com.balancesentinel.app.data.model.GitHubRelease?>(null) }
                var autoUpdateCurrentVersion by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    if (BatteryOptimizationHelper.shouldShowGuide(context)) showBatteryGuide = true
                }
                LaunchedEffect(currentRoute) {
                    if (currentRoute == AppRoute.Settings.route && !updateCheckPerformed) {
                        updateCheckPerformed = true
                        val prefs = UpdatePrefs(context)
                        if (prefs.shouldAutoCheckToday()) {
                            when (val result = withContext(Dispatchers.IO) { UpdateChecker().checkForUpdate(context) }) {
                                is UpdateResult.UpdateAvailable -> if (!prefs.shouldSkipVersion(result.release.tagName)) {
                                    autoUpdateRelease = result.release
                                    autoUpdateCurrentVersion = result.currentVersion
                                    showAutoUpdateDialog = true
                                    prefs.markPromptedToday()
                                }
                                else -> Unit
                            }
                        }
                    }
                }

                if (showBatteryGuide) {
                    AlertDialog(
                        onDismissRequest = { showBatteryGuide = false; BatteryOptimizationHelper.recordDismiss(context) },
                        title = { Text(stringResource(R.string.settings_battery_guide_title)) },
                        text = { Text(stringResource(R.string.settings_battery_guide_desc)) },
                        confirmButton = {
                            TextButton(onClick = {
                                BatteryOptimizationHelper.markGuideShown(context)
                                BatteryOptimizationHelper.openBatterySettings(context)
                                showBatteryGuide = false
                            }) { Text(stringResource(R.string.settings_close_battery_opt)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatteryGuide = false; BatteryOptimizationHelper.recordDismiss(context) }) {
                                Text(stringResource(R.string.settings_later))
                            }
                        }
                    )
                }
                if (showAutoUpdateDialog && autoUpdateRelease != null) {
                    UpdateDialog(
                        release = autoUpdateRelease!!,
                        currentVersion = autoUpdateCurrentVersion,
                        onDismiss = { showAutoUpdateDialog = false },
                        onSkipVersion = {
                            UpdatePrefs(context).skippedVersion = autoUpdateRelease!!.tagName
                            showAutoUpdateDialog = false
                        },
                        onRemindLater = { showAutoUpdateDialog = false }
                    )
                }
                WalletNavHost(
                    startDestination = requestedStart,
                    onRouteChanged = { currentRoute = it }
                )
            }
        }
        CrashLogger.breadcrumb("MainActivity", "onCreate complete")
    }

    private fun requestNotificationAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startRefreshService()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else startRefreshService()
    }

    private fun startRefreshService() {
        RefreshScheduler.markStartRequested(this)
        try {
            ContextCompat.startForegroundService(this, Intent(this, BalanceRefreshService::class.java))
        } catch (e: Exception) {
            com.balancesentinel.app.data.util.Logger.e("MainActivity", "Failed to start refresh service", e)
        }
    }
}
