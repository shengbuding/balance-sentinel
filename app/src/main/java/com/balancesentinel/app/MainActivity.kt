package com.balancesentinel.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.data.update.UpdateChecker
import com.balancesentinel.app.data.update.UpdatePrefs
import com.balancesentinel.app.data.update.UpdateResult
import com.balancesentinel.app.ui.navigation.AppRoute
import com.balancesentinel.app.ui.navigation.DeepLinkResolver
import com.balancesentinel.app.ui.navigation.WalletNavHost
import com.balancesentinel.app.ui.screen.UpdateDialog
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.ui.viewmodel.CapabilityUiEvent
import com.balancesentinel.app.ui.viewmodel.CapabilityViewModel
import com.balancesentinel.app.util.BatteryOptimizationHelper
import com.balancesentinel.app.util.OnboardingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val capabilityViewModel: CapabilityViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val canAskAgain = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        capabilityViewModel.onNotificationPermissionResult(granted, canAskAgain)
    }

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
        // Foreground monitoring and notification permission are user initiated.

        val accountIds = readRoomAccountIds()
        val requestedStart = resolveStartDestination(
            intent = intent,
            accountIds = accountIds,
            showOnboarding = OnboardingHelper.shouldShow(this)
        )

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
                    capabilityViewModel.events.collect { event ->
                        when (event) {
                            CapabilityUiEvent.RequestNotificationPermission -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    capabilityViewModel.onNotificationPermissionResult(
                                        granted = true,
                                        canAskAgain = true
                                    )
                                }
                            }
                            CapabilityUiEvent.OpenAppSettings -> {
                                startActivity(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(android.net.Uri.parse("package:$packageName"))
                                )
                            }
                            CapabilityUiEvent.OpenExactAlarmSettings -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    startActivity(
                                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(android.net.Uri.parse("package:$packageName"))
                                    )
                                }
                            }
                            CapabilityUiEvent.OpenBatteryOptimizationSettings -> {
                                if (BatteryOptimizationHelper.openBatterySettings(this@MainActivity)) {
                                    BatteryOptimizationHelper.markGuideShown(this@MainActivity)
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(currentRoute) {
                    if (currentRoute != AppRoute.Onboarding.route &&
                        BatteryOptimizationHelper.shouldShowGuide(context)
                    ) showBatteryGuide = true
                }
                LaunchedEffect(currentRoute) {
                    if (currentRoute == AppRoute.Settings.route && !updateCheckPerformed) {
                        updateCheckPerformed = true
                        val prefs = UpdatePrefs(context)
                        if (prefs.autoCheckEnabled && prefs.shouldAutoCheckToday()) {
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
                                if (BatteryOptimizationHelper.openBatterySettings(context)) {
                                    BatteryOptimizationHelper.markGuideShown(context)
                                }
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
                    onRouteChanged = { currentRoute = it },
                    capabilityViewModel = capabilityViewModel
                )
            }
        }
        CrashLogger.breadcrumb("MainActivity", "onCreate complete")
    }

    override fun onResume() {
        super.onResume()
        capabilityViewModel.refresh()
    }

    private fun readRoomAccountIds(): Set<String> = try {
        runBlocking(Dispatchers.IO) {
            val accountState = RoomAccountUiRepository(
                RoomAccountRepository(WalletDatabaseProvider.get(this@MainActivity)),
                EncryptedPreferencesCredentialStore(this@MainActivity)
            ).observe().first { it !is AccountLoadState.Loading }
            (accountState as? AccountLoadState.Ready)
                ?.accounts
                ?.mapTo(mutableSetOf()) { it.id }
                .orEmpty()
        }
    } catch (_: Exception) {
        emptySet()
    }

    companion object {
        @JvmStatic
        fun resolveStartDestination(
            intent: Intent?,
            accountIds: Set<String>,
            showOnboarding: Boolean
        ): String {
            if (showOnboarding) return AppRoute.Onboarding.route
            val hasDeepLink = intent?.data != null || intent?.extras?.let {
                it.containsKey(AppRoute.LEGACY_TARGET_EXTRA) ||
                    it.containsKey(AppRoute.LEGACY_ACCOUNT_EXTRA) ||
                    it.containsKey(AppRoute.LEGACY_CURRENCY_EXTRA)
            } == true
            if (!hasDeepLink) return AppRoute.Home.route
            val result = DeepLinkResolver.resolve(intent, accountIds)
            return if (
                result is com.balancesentinel.app.ui.navigation.DeepLinkResult.InvalidDeepLink &&
                isInsightsIntent(intent)
            ) {
                "insights"
            } else {
                result.route.route
            }
        }

        private fun isInsightsIntent(intent: Intent): Boolean {
            val uri = intent.data
            if (uri != null) {
                return uri.scheme.equals(AppRoute.SCHEME, ignoreCase = true) &&
                    uri.host.equals(AppRoute.INSIGHTS_HOST, ignoreCase = true)
            }
            return intent.getStringExtra(AppRoute.LEGACY_TARGET_EXTRA)
                .equals("insights", ignoreCase = true)
        }
    }

}
