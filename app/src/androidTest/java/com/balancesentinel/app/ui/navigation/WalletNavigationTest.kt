package com.balancesentinel.app.ui.navigation

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Navigation contract tests for the single wallet NavHost. */
class WalletNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsAboutUsesSystemBackStack() {
        val controller = TestNavHostController(ApplicationProvider.getApplicationContext<Context>())
        composeRule.setContent {
            controller.navigatorProvider.addNavigator(ComposeNavigator())
            WalletNavHost(navController = controller, startDestination = AppRoute.Home.route)
        }
        composeRule.runOnIdle {
            controller.navigate(AppRoute.Settings.route)
            controller.navigate(AppRoute.About.route)
        }
        assertEquals(AppRoute.About.route, controller.currentDestination?.route)
        controller.popBackStack()
        assertEquals(AppRoute.Settings.route, controller.currentDestination?.route)
    }

    @Test
    fun tabNavigationIsSingleTop() {
        val controller = TestNavHostController(ApplicationProvider.getApplicationContext<Context>())
        composeRule.setContent {
            controller.navigatorProvider.addNavigator(ComposeNavigator())
            WalletNavHost(navController = controller, startDestination = AppRoute.Home.route)
        }
        composeRule.runOnIdle {
            controller.navigate("insights") { launchSingleTop = true }
            controller.navigate("insights") { launchSingleTop = true }
        }
        assertEquals("insights", controller.currentDestination?.route)
        assertEquals(1, controller.currentBackStack.value.count { it.destination.route == "insights" })
    }
}
