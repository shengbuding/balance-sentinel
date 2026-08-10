package com.balancesentinel.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.balancesentinel.app.ui.CustomIcons

/** Compatibility seam for existing navigation UI tests; production uses WalletNavHost. */
enum class Screen { HOME, INSIGHTS, SETTINGS, CONSOLE_SELECT }

@Composable
fun AppNavigationBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(selected = currentScreen == Screen.HOME, onClick = { onScreenSelected(Screen.HOME) }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text(stringResource(R.string.home_title)) })
        NavigationBarItem(selected = currentScreen == Screen.INSIGHTS, onClick = { onScreenSelected(Screen.INSIGHTS) }, icon = { Icon(CustomIcons.TrendingUp, null) }, label = { Text(stringResource(R.string.insights_title)) })
        NavigationBarItem(selected = currentScreen == Screen.CONSOLE_SELECT, onClick = { onScreenSelected(Screen.CONSOLE_SELECT) }, icon = { Icon(CustomIcons.Analytics, null) }, label = { Text("Console") })
        NavigationBarItem(selected = currentScreen == Screen.SETTINGS, onClick = { onScreenSelected(Screen.SETTINGS) }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.settings_title)) })
    }
}