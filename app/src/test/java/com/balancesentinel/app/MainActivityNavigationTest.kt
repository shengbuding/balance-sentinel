package com.balancesentinel.app

import android.content.Intent
import android.net.Uri
import com.balancesentinel.app.ui.navigation.AppRoute
import com.balancesentinel.app.ui.navigation.topLevelTabForRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationTest {
    private val accounts = setOf("acct-1")

    @Test fun noLinkStartsHome() = assertEquals(AppRoute.Home.route, MainActivity.resolveStartDestination(Intent(), accounts, false))
    @Test fun onboardingTakesPrecedence() {
        val intent = Intent().setData(AppRoute.Insights("acct-1", "USD").toUri())
        assertEquals(AppRoute.Onboarding.route, MainActivity.resolveStartDestination(intent, accounts, true))
    }
    @Test fun canonicalValidLinkResolves() {
        val intent = Intent().setData(AppRoute.Insights("acct-1", "USD").toUri())
        assertEquals("insights/acct-1/USD", MainActivity.resolveStartDestination(intent, accounts, false))
    }
    @Test fun unknownAndMalformedInsightsLinksFallBackToInsightsHome() {
        val unknown = Intent().setData(Uri.parse("balancesentinel://insights/unknown/USD"))
        assertEquals("insights", MainActivity.resolveStartDestination(unknown, accounts, false))
        val malformed = Intent().setData(Uri.parse("balancesentinel://insights/acct-1/USD/extra"))
        assertEquals("insights", MainActivity.resolveStartDestination(malformed, accounts, false))
    }
    @Test fun legacyIncompleteAndNonIsoCurrencyFallBackToInsightsHome() {
        val incomplete = Intent().putExtra(AppRoute.LEGACY_TARGET_EXTRA, "insights")
        assertEquals("insights", MainActivity.resolveStartDestination(incomplete, accounts, false))
        val invalidCurrency = Intent().apply {
            putExtra(AppRoute.LEGACY_TARGET_EXTRA, "insights")
            putExtra(AppRoute.LEGACY_ACCOUNT_EXTRA, "acct-1")
            putExtra(AppRoute.LEGACY_CURRENCY_EXTRA, "not-a-currency")
        }
        assertEquals("insights", MainActivity.resolveStartDestination(invalidCurrency, accounts, false))
    }
    @Test fun unsupportedTargetsRemainInvalid() {
        val unsupported = Intent().setData(Uri.parse("balancesentinel://settings"))
        assertEquals(
            AppRoute.InvalidDeepLink("UnsupportedTarget").route,
            MainActivity.resolveStartDestination(unsupported, accounts, false)
        )
    }
    @Test fun childRoutesKeepOwningTabSelected() {
        assertEquals(AppRoute.Settings.route, topLevelTabForRoute(AppRoute.Log.route))
        assertEquals(AppRoute.Settings.route, topLevelTabForRoute(AppRoute.BackupRestore.route))
        assertEquals(AppRoute.ConsoleSelect.route, topLevelTabForRoute(AppRoute.AddPlatform.route))
        assertEquals(AppRoute.ConsoleSelect.route, topLevelTabForRoute(AppRoute.Console("acct").route))
    }
}
