package com.balancesentinel.app.ui.navigation

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.balancesentinel.app.widget.StaticWidgetProvider

@RunWith(RobolectricTestRunner::class)
class DeepLinkResolverTest {
    private val accounts = setOf("account-1", "account-2")

    @Test
    fun `canonical account currency deep link selects exact insights target`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights/account-1/cny"), accounts
        )

        assertEquals(
            DeepLinkResult.Resolved(AppRoute.Insights("account-1", "CNY")),
            result
        )
    }

    @Test
    fun `missing account is an invalid deep link`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights?currency=USD"), accounts
        )

        assertEquals(
            DeepLinkResult.InvalidDeepLink(InvalidReason.MissingAccount),
            result
        )
    }

    @Test
    fun `deleted account is an invalid deep link`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights/deleted-account/USD"), accounts
        )

        assertEquals(
            DeepLinkResult.InvalidDeepLink(InvalidReason.UnknownAccount),
            result
        )
    }

    @Test
    fun `missing currency is an invalid deep link`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights/account-1"), accounts
        )

        assertEquals(
            DeepLinkResult.InvalidDeepLink(InvalidReason.MissingCurrency),
            result
        )
    }

    @Test
    fun `non ISO currency is an invalid deep link`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights/account-1/POINTS"), accounts
        )

        assertEquals(
            DeepLinkResult.InvalidDeepLink(InvalidReason.InvalidCurrency),
            result
        )
    }

    @Test
    fun `legacy extras use the same resolver and select exact target`() {
        val intent = Intent().apply {
            putExtra(AppRoute.LEGACY_TARGET_EXTRA, "insights")
            putExtra(AppRoute.LEGACY_ACCOUNT_EXTRA, "account-2")
            putExtra(AppRoute.LEGACY_CURRENCY_EXTRA, "eur")
        }

        val result = DeepLinkResolver.resolve(intent, accounts)

        assertEquals(
            DeepLinkResult.Resolved(AppRoute.Insights("account-2", "EUR")),
            result
        )
    }

    @Test
    fun `canonical route URI is stable and normalized`() {
        val uri = AppRoute.Insights("account-1", "cny").toUri()

        assertEquals("balancesentinel://insights/account-1/CNY", uri.toString())
        assertTrue(uri.pathSegments == listOf("account-1", "CNY"))
    }
    @Test
    fun `invalid result exposes typed InvalidDeepLink route`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights/account-1/POINTS"), accounts
        ) as DeepLinkResult.InvalidDeepLink

        assertEquals(AppRoute.InvalidDeepLink(InvalidReason.InvalidCurrency.name), result.route)
    }

    @Test
    fun `extra URI path segments are an invalid deep link`() {
        val result = DeepLinkResolver.resolve(
            Uri.parse("balancesentinel://insights/account-1/USD/extra"), accounts
        )

        assertEquals(DeepLinkResult.InvalidDeepLink(InvalidReason.MalformedUri), result)
    }

    @Test
    fun `stale widget account cannot produce an account deep link`() {
        assertEquals(
            null,
            StaticWidgetProvider.configuredDeepLinkAccountId("deleted-account", accounts)
        )
        assertEquals(
            "account-1",
            StaticWidgetProvider.configuredDeepLinkAccountId("account-1", accounts)
        )
    }

}
