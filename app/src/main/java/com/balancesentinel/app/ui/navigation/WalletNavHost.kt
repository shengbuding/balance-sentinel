package com.balancesentinel.app.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.balancesentinel.app.R
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.console.AddPlatformScreen
import com.balancesentinel.app.ui.console.ConsoleScreen
import com.balancesentinel.app.ui.console.ConsoleSelectScreen
import com.balancesentinel.app.ui.screen.AboutScreen
import com.balancesentinel.app.ui.screen.AlertSettingsScreen
import com.balancesentinel.app.ui.screen.BackupRestoreRouteScreen
import com.balancesentinel.app.ui.screen.ClearDataRouteScreen
import com.balancesentinel.app.ui.screen.DataManagementScreen
import com.balancesentinel.app.ui.screen.HomeScreen
import com.balancesentinel.app.ui.screen.InsightsScreen
import com.balancesentinel.app.ui.screen.LogScreen
import com.balancesentinel.app.ui.screen.OnboardingScreen
import com.balancesentinel.app.ui.screen.RefreshSettingsScreen
import com.balancesentinel.app.ui.screen.SettingsScreen
import com.balancesentinel.app.ui.screen.SystemStatusScreen
import com.balancesentinel.app.ui.viewmodel.ConsoleViewModel
import com.balancesentinel.app.ui.viewmodel.DataManagementViewModel
import com.balancesentinel.app.ui.viewmodel.HomeViewModel
import com.balancesentinel.app.ui.viewmodel.InsightsViewModel
import com.balancesentinel.app.ui.viewmodel.LogViewModel

@Composable
fun WalletNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppRoute.Home.route,
    onRouteChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route.orEmpty()
    val currentTab = topLevelTabForRoute(currentRoute)

    LaunchedEffect(currentRoute) { onRouteChanged(currentRoute) }

    val homeViewModel: HomeViewModel = viewModel()
    val insightsViewModel: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory(application))
    val logViewModel: LogViewModel = viewModel(factory = LogViewModel.Factory(application))
    val dataManagementViewModel: DataManagementViewModel = viewModel()
    val showBottomBar = currentRoute != AppRoute.Onboarding.route && currentRoute != AppRoute.InvalidDeepLink().route

    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(AppRoute.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) NavigationBar {
                NavigationBarItem(
                    selected = currentTab == AppRoute.Home.route,
                    onClick = { navigateTab(AppRoute.Home.route) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.home_title)) },
                    label = { Text(stringResource(R.string.home_title)) }
                )
                NavigationBarItem(
                    selected = currentTab == "insights",
                    onClick = { navigateTab("insights") },
                    icon = { Icon(CustomIcons.TrendingUp, contentDescription = stringResource(R.string.insights_title)) },
                    label = { Text(stringResource(R.string.insights_title)) }
                )
                NavigationBarItem(
                    selected = currentTab == AppRoute.ConsoleSelect.route,
                    onClick = { navigateTab(AppRoute.ConsoleSelect.route) },
                    icon = { Icon(CustomIcons.Analytics, contentDescription = "Console") },
                    label = { Text("Console") }
                )
                NavigationBarItem(
                    selected = currentTab == AppRoute.Settings.route,
                    onClick = { navigateTab(AppRoute.Settings.route) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title)) },
                    label = { Text(stringResource(R.string.settings_title)) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(AppRoute.Onboarding.route) {
                OnboardingScreen(onComplete = {
                    navController.navigate(AppRoute.Home.route) {
                        popUpTo(AppRoute.Onboarding.route) { inclusive = true }
                    }
                })
            }
            composable(AppRoute.Home.route) {
                LaunchedEffect(Unit) { homeViewModel.loadCachedBalances() }
                HomeScreen(homeViewModel) { navigateTab(AppRoute.Settings.route) }
            }
            composable("insights") {
                LaunchedEffect(Unit) { insightsViewModel.loadData() }
                InsightsScreen(insightsViewModel)
            }
            composable(
                route = "insights/{accountId}/{currency}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }, navArgument("currency") { type = NavType.StringType })
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId")
                val currency = entry.arguments?.getString("currency")
                LaunchedEffect(accountId, currency) {
                    if (!accountId.isNullOrBlank()) insightsViewModel.selectAccount(accountId)
                    if (!currency.isNullOrBlank()) insightsViewModel.selectCurrency(currency)
                    else insightsViewModel.loadData()
                }
                InsightsScreen(insightsViewModel)
            }
            composable(AppRoute.Settings.route) {
                LaunchedEffect(Unit) { homeViewModel.loadStatusSummary() }
                SettingsScreen(
                    viewModel = homeViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToLog = { navController.navigate(AppRoute.Log.route) },
                    onNavigateToDataManagement = { navController.navigate(AppRoute.DataHub.route) },
                    onNavigateToAlertSettings = { navController.navigate(AppRoute.AlertSettings.route) },
                    onNavigateToRefresh = { navController.navigate(AppRoute.RefreshSettings.route) },
                    onNavigateToSystemStatus = { navController.navigate(AppRoute.SystemStatus.route) },
                    onNavigateToAbout = { navController.navigate(AppRoute.About.route) }
                )
            }
            composable(AppRoute.RefreshSettings.route) { RefreshSettingsScreen(homeViewModel) { navController.popBackStack() } }
            composable(AppRoute.SystemStatus.route) { SystemStatusScreen(homeViewModel, { navController.popBackStack() }) { navController.navigate(AppRoute.Log.route) } }
            composable(AppRoute.About.route) { AboutScreen { navController.popBackStack() } }
            composable(AppRoute.Log.route) { LogScreen(logViewModel) { navController.popBackStack() } }
            composable(AppRoute.DataHub.route) {
                DataManagementScreen(
                    dataManagementViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToClear = { navController.navigate(AppRoute.ClearData.route) },
                    onNavigateToBackup = { navController.navigate(AppRoute.BackupRestore.route) }
                )
            }
            composable(AppRoute.ClearData.route) { ClearDataRouteScreen(dataManagementViewModel) { navController.popBackStack() } }
            composable(AppRoute.BackupRestore.route) { BackupRestoreRouteScreen(dataManagementViewModel, { navController.popBackStack() }, homeViewModel::loadCachedBalances) }
            composable(AppRoute.AlertSettings.route) { AlertSettingsScreen(homeViewModel) { navController.popBackStack() } }
            composable(AppRoute.ConsoleSelect.route) {
                ConsoleSelectScreen(
                    onSelectPlatform = { platform -> navController.navigate(AppRoute.Console(platform.id).route) },
                    onAddPlatform = { navController.navigate(AppRoute.AddPlatform.route) },
                    refreshTrigger = 0
                )
            }
            composable(AppRoute.AddPlatform.route) {
                val store = remember { ConsoleStore(context) }
                AddPlatformScreen(
                    addedPlatformIds = store.getPlatforms().map { it.id },
                    onAddPreset = { platform -> store.addPlatform(platform); navController.navigate(AppRoute.Console(platform.id).route) { popUpTo(AppRoute.AddPlatform.route) { inclusive = true } } },
                    onAddCustom = { platform -> store.addPlatform(platform); navController.navigate(AppRoute.Console(platform.id).route) { popUpTo(AppRoute.AddPlatform.route) { inclusive = true } } },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "console/{platformId}",
                arguments = listOf(navArgument("platformId") { type = NavType.StringType })
            ) { entry ->
                val platformId = entry.arguments?.getString("platformId").orEmpty()
                val platform = remember(platformId) { ConsoleStore(context).getPlatforms().firstOrNull { it.id == platformId } }
                if (platform == null) {
                    LaunchedEffect(platformId) {
                        navController.navigate(AppRoute.InvalidDeepLink("unknown_platform").route) {
                            popUpTo(AppRoute.Console(platformId).route) { inclusive = true }
                        }
                    }
                } else {
                    val consoleViewModel: ConsoleViewModel = viewModel(
                        key = "console_$platformId",
                        factory = ConsoleViewModel.Factory(application, platform)
                    )
                    val uiState by consoleViewModel.uiState.collectAsStateWithLifecycle()
                    ConsoleScreen(
                        platform = platform,
                        uiState = uiState,
                        onLoginSuccess = consoleViewModel::onLoginSuccess,
                        onLogout = { consoleViewModel.logout(); navController.popBackStack(AppRoute.ConsoleSelect.route, false) },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(AppRoute.InvalidDeepLink().route) {
                InvalidDeepLinkScreen(onBack = {
                    if (!navController.popBackStack()) navController.navigate(AppRoute.Home.route)
                })
            }
        }
    }
}

internal fun topLevelTabForRoute(route: String): String? = when {
    route == AppRoute.Home.route -> AppRoute.Home.route
    route.startsWith("insights") -> "insights"
    route.startsWith("console") || route == AppRoute.AddPlatform.route -> AppRoute.ConsoleSelect.route
    route.startsWith("settings") || route == AppRoute.About.route ||
        route == AppRoute.RefreshSettings.route || route == AppRoute.SystemStatus.route ||
        route == AppRoute.Log.route || route == AppRoute.DataHub.route ||
        route == AppRoute.ClearData.route || route == AppRoute.BackupRestore.route ||
        route == AppRoute.AlertSettings.route -> AppRoute.Settings.route
    else -> null
}
