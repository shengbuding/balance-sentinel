package com.balancesentinel.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.PERCENTAGE_CURRENCY
import com.balancesentinel.app.data.api.QuotaPeriodSnapshot
import com.balancesentinel.app.data.api.QuotaSnapshot
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

    @Test
    fun `subscription widget renders percentage and all quota windows`() {
        val state = WidgetViewState.Fresh(
            selection = WidgetSelection(
                "account-1",
                PERCENTAGE_CURRENCY,
                "Primary",
                quotaPeriod = WidgetConfig.DEFAULT_QUOTA_PERIOD
            ),
            balance = AggregatedBalance(
                totalBalance = "10",
                currency = PERCENTAGE_CURRENCY,
                isAvailable = true,
                grantedBalance = "",
                toppedUpBalance = "",
                accountCount = 1,
                lastUpdated = 1,
                quota = QuotaSnapshot(
                    listOf(
                        QuotaPeriodSnapshot("rolling_5h", 10.0, 90.0),
                        QuotaPeriodSnapshot("weekly", 25.0, 75.0),
                        QuotaPeriodSnapshot("monthly", 17.0, 83.0)
                    )
                )
            )
        )

        val model = WidgetRemoteViewsRenderer.model(context, state, expanded = true)

        assertEquals(
            context.getString(
                R.string.widget_subscription_primary,
                context.getString(R.string.widget_subscription_5h),
                "10%"
            ),
            model.balance
        )
        assertEquals(context.getString(R.string.widget_status_subscription), model.status)
        assertEquals(3, model.subscriptionDetails.size)
        assertTrue(model.subscriptionDetails[0].contains("10%"))
        assertTrue(model.subscriptionDetails[1].contains("25%"))
        assertTrue(model.subscriptionDetails[2].contains("17%"))
        assertTrue(model.granted.isEmpty())
        assertTrue(model.toppedUp.isEmpty())
    }

    @Test
    fun `subscription widget primary value follows selected quota period`() {
        val quota = QuotaSnapshot(
            listOf(
                QuotaPeriodSnapshot("rolling_5h", 10.0, 90.0),
                QuotaPeriodSnapshot("weekly", 25.0, 75.0),
                QuotaPeriodSnapshot("monthly", 17.0, 83.0)
            )
        )
        val cases = listOf(
            Triple("rolling_5h", 10.0, R.string.widget_subscription_5h),
            Triple("weekly", 25.0, R.string.widget_subscription_weekly),
            Triple("monthly", 17.0, R.string.widget_subscription_monthly)
        )

        cases.forEach { (period, used, labelRes) ->
            val state = WidgetViewState.Fresh(
                selection = WidgetSelection(
                    "account-1",
                    PERCENTAGE_CURRENCY,
                    "Primary",
                    quotaPeriod = period
                ),
                balance = AggregatedBalance(
                    totalBalance = used.toString(),
                    currency = PERCENTAGE_CURRENCY,
                    isAvailable = true,
                    grantedBalance = "",
                    toppedUpBalance = "",
                    accountCount = 1,
                    lastUpdated = 1,
                    quota = quota
                )
            )

            val model = WidgetRemoteViewsRenderer.model(context, state, expanded = false)
            val expected = context.getString(
                R.string.widget_subscription_primary,
                context.getString(labelRes),
                "${used.toInt()}%"
            )
            assertEquals(expected, model.balance)
        }
    }

    @Test
    fun `widget title is single line marquee for long wallet names`() {
        val state = WidgetViewState.Fresh(
            selection = WidgetSelection(
                "account-1",
                "USD",
                "A very long wallet name that must not take balance space"
            ),
            balance = AggregatedBalance(
                totalBalance = "12.50",
                currency = "USD",
                isAvailable = true,
                grantedBalance = "1",
                toppedUpBalance = "2",
                accountCount = 1,
                lastUpdated = 1
            )
        )

        val (remoteViews, _) = WidgetRemoteViewsRenderer.render(
            context,
            R.layout.widget_balance,
            state,
            expanded = true
        )
        val root = remoteViews.apply(context, FrameLayout(context))
        val title = root.findViewById<TextView>(R.id.widget_title)

        assertTrue(title.isFocusable)
        assertTrue(title.isFocusableInTouchMode)
        assertEquals(1, title.maxLines)
        assertEquals(TextUtils.TruncateAt.MARQUEE, title.ellipsize)
    }

    private fun localizedContext(languageTag: String): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
        return context.createConfigurationContext(configuration)
    }
}
