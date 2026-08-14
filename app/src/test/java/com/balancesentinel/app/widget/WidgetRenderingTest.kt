package com.balancesentinel.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetRenderingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `unconfigured render asks user to configure and uses home action`() {
        val state = WidgetViewState.Unconfigured(reason = WidgetViewState.Unconfigured.Reason.MISSING_CONFIG)
        val model = WidgetRemoteViewsRenderer.model(context, state, expanded = true)

        assertEquals(context.getString(R.string.widget_state_unconfigured_status), model.status)
        assertEquals(WidgetPrimaryAction.CONFIGURE, model.primaryAction)
        assertTrue(
            model.balance.contains("configure", ignoreCase = true) ||
                model.balance.contains("choose", ignoreCase = true) ||
                model.balance.contains("选择")
        )
    }

    @Test
    fun `stale selected account retains balance and insights deep link`() {
        val state = WidgetViewState.Stale(
            selection = WidgetSelection("account-1", "USD", "Primary"),
            balance = AggregatedBalance("12.50", "USD", isAvailable = false, grantedBalance = "1", toppedUpBalance = "2", accountCount = 1, lastUpdated = 1_800_000_000_000L),
            refreshState = null
        )
        val model = WidgetRemoteViewsRenderer.model(context, state, expanded = true, now = 1_800_000_100_000L)

        assertEquals(context.getString(R.string.widget_state_stale_status), model.status)
        assertEquals(WidgetPrimaryAction.OPEN_INSIGHTS, model.primaryAction)
        assertTrue(model.showDetails)
        assertTrue(model.refreshTime.isNotEmpty())
    }

    @Test
    fun `permission restricted render hides cached balance details`() {
        val state = WidgetViewState.PermissionRestricted(
            selection = WidgetSelection("account-1", "USD", "Primary"),
            balance = AggregatedBalance("12.50", "USD", isAvailable = true, grantedBalance = "1", toppedUpBalance = "2", accountCount = 1, lastUpdated = 1)
        )
        val model = WidgetRemoteViewsRenderer.model(context, state, expanded = true)

        assertEquals(context.getString(R.string.widget_state_permission_restricted_status), model.status)
        assertEquals(context.getString(R.string.widget_state_permission_restricted_balance), model.balance)
        assertTrue(!model.showDetails)
    }

    @Test
    fun `compact widget uses short currency symbol in chinese locale`() {
        val chineseContext = localizedContext("zh-CN")
        val state = WidgetViewState.Fresh(
            selection = WidgetSelection("account-1", "USD", "Primary"),
            balance = AggregatedBalance(
                totalBalance = "0",
                currency = "USD",
                isAvailable = true,
                grantedBalance = "0",
                toppedUpBalance = "0",
                accountCount = 1,
                lastUpdated = 1
            )
        )

        val model = WidgetRemoteViewsRenderer.model(chineseContext, state, expanded = false)

        assertEquals("\$0.00", model.balance)
    }

    private fun localizedContext(languageTag: String): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
        return context.createConfigurationContext(configuration)
    }
}
