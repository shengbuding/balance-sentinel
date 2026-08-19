package com.balancesentinel.app.widget

import android.content.Context
import android.widget.RemoteViews
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.PERCENTAGE_CURRENCY
import com.balancesentinel.app.data.api.quotaPeriodRank
import com.balancesentinel.app.data.api.quotaResetEpochMillis
import com.balancesentinel.app.ui.navigation.AppRoute
import com.balancesentinel.app.util.FormatUtils
import com.balancesentinel.app.util.LocalizedFormatter

enum class WidgetPrimaryAction { CONFIGURE, OPEN_HOME, OPEN_INSIGHTS }

data class WidgetRenderModel(
    val title: String,
    val status: String,
    val balance: String,
    val granted: String,
    val toppedUp: String,
    val subscriptionDetails: List<String>,
    val refreshTime: String,
    val showDetails: Boolean,
    val primaryAction: WidgetPrimaryAction,
    val route: AppRoute
)

object WidgetRemoteViewsRenderer {
    fun model(context: Context, state: WidgetViewState, expanded: Boolean, now: Long = System.currentTimeMillis()): WidgetRenderModel {
        val formatter = LocalizedFormatter(context)
        val selection = state.selection
        val title = when (state) {
            is WidgetViewState.Unconfigured -> context.getString(R.string.widget_state_unconfigured_title)
            else -> when {
                selection.total -> context.getString(R.string.widget_title_total)
                !selection.label.isNullOrBlank() -> selection.label
                else -> context.getString(R.string.widget_default_title)
            }
        }
        val balance = (state as? WidgetViewState.Fresh)?.balance
            ?: (state as? WidgetViewState.Stale)?.balance
        val balanceText = when (state) {
            is WidgetViewState.Fresh, is WidgetViewState.Stale -> balance?.let {
                formatBalanceDisplay(
                    context = context,
                    formatter = formatter,
                    balance = it,
                    compact = !expanded,
                    quotaPeriod = selection.quotaPeriod ?: WidgetConfig.DEFAULT_QUOTA_PERIOD
                )
            }
                ?: context.getString(R.string.widget_query_balance)
            is WidgetViewState.PermissionRestricted -> context.getString(R.string.widget_state_permission_restricted_balance)
            is WidgetViewState.Unconfigured -> context.getString(R.string.widget_state_configure_prompt)
            else -> context.getString(R.string.widget_query_balance)
        }
        val status = when (state) {
            is WidgetViewState.Unconfigured -> context.getString(R.string.widget_state_unconfigured_status)
            is WidgetViewState.NoData -> context.getString(R.string.widget_state_no_data_status)
            is WidgetViewState.Fresh -> if (state.balance.currency == PERCENTAGE_CURRENCY) {
                context.getString(R.string.widget_status_subscription)
            } else if (state.balance.isAvailable) {
                context.getString(R.string.widget_status_available)
            } else context.getString(R.string.widget_status_partial)
            is WidgetViewState.Stale -> context.getString(R.string.widget_state_stale_status)
            is WidgetViewState.PermissionRestricted -> context.getString(R.string.widget_state_permission_restricted_status)
            is WidgetViewState.RefreshFailed -> context.getString(R.string.widget_state_refresh_failed_status)
        }
        val showDetails = expanded && balance != null && state !is WidgetViewState.PermissionRestricted
        val isSubscription = balance?.currency == PERCENTAGE_CURRENCY
        val granted = balance?.takeUnless { isSubscription }?.let {
            context.getString(
                R.string.balance_granted,
                formatSignedValue(formatter, it.grantedBalance, it.currency)
            )
        }.orEmpty()
        val toppedUp = balance?.takeUnless { isSubscription }?.let {
            context.getString(
                R.string.balance_topped_up,
                formatSignedValue(formatter, it.toppedUpBalance, it.currency)
            )
        }.orEmpty()
        val subscriptionDetails = if (isSubscription) {
            listOf(
                "rolling_5h" to R.string.widget_subscription_5h,
                "weekly" to R.string.widget_subscription_weekly,
                "monthly" to R.string.widget_subscription_monthly
            ).map { (periodId, labelRes) ->
                val period = balance?.quota?.find(periodId)
                val value = period?.let { formatter.formatNumber(it.usedPercent, 0, 1) + "%" } ?: "--"
                val reset = period?.resetsAt?.let(::quotaResetEpochMillis)?.let { resetAt ->
                    " · " + formatter.formatDateTime(resetAt)
                }.orEmpty()
                "${context.getString(labelRes)} $value$reset"
            }
        } else {
            emptyList()
        }
        val refreshTime = balance?.lastUpdated?.takeIf { it > 0 }?.let {
            formatter.formatRelativeTime(it, now)
        }.orEmpty()
        val route = if (selection.accountId != null && selection.currency != null) {
            AppRoute.Insights(selection.accountId, selection.currency)
        } else {
            AppRoute.Home
        }
        return WidgetRenderModel(
            title = title,
            status = status,
            balance = balanceText,
            granted = granted,
            toppedUp = toppedUp,
            subscriptionDetails = subscriptionDetails,
            refreshTime = refreshTime,
            showDetails = showDetails,
            primaryAction = when (state) {
                is WidgetViewState.Unconfigured -> WidgetPrimaryAction.CONFIGURE
                is WidgetViewState.Fresh, is WidgetViewState.Stale ->
                    if (route is AppRoute.Insights) WidgetPrimaryAction.OPEN_INSIGHTS else WidgetPrimaryAction.OPEN_HOME
                else -> WidgetPrimaryAction.OPEN_HOME
            },
            route = route
        )
    }

    fun render(
        context: Context,
        layoutRes: Int,
        state: WidgetViewState,
        expanded: Boolean,
        now: Long = System.currentTimeMillis()
    ): Pair<RemoteViews, WidgetRenderModel> {
        val model = model(context, state, expanded, now)
        val views = RemoteViews(context.packageName, layoutRes)
        views.setTextViewText(R.id.widget_title, model.title)
        views.setTextViewText(R.id.widget_status, model.status)
        views.setTextViewText(R.id.widget_balance, model.balance)
        views.setTextViewText(R.id.widget_refresh_time, model.refreshTime)
        if (expanded) {
            views.setTextViewText(R.id.widget_granted, model.granted)
            views.setTextViewText(R.id.widget_topped_up, model.toppedUp)
            val quotaIds = listOf(
                R.id.widget_subscription_5h,
                R.id.widget_subscription_weekly,
                R.id.widget_subscription_monthly
            )
            model.subscriptionDetails.take(3).forEachIndexed { index, text ->
                views.setTextViewText(quotaIds[index], text)
            }
            views.setViewVisibility(
                R.id.widget_detail_row,
                if (model.showDetails && model.subscriptionDetails.isEmpty()) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            )
            views.setViewVisibility(
                R.id.widget_subscription_detail_row,
                if (model.showDetails && model.subscriptionDetails.isNotEmpty()) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            )
        }
        return views to model
    }

    private fun formatBalanceDisplay(
        context: Context,
        formatter: LocalizedFormatter,
        balance: AggregatedBalance,
        compact: Boolean,
        quotaPeriod: String
    ): String {
        if (balance.currency == PERCENTAGE_CURRENCY) {
            val value = balance.totalBalance.toDoubleOrNull() ?: 0.0
            val percentage = formatter.formatNumber(value, 0, 1) + "%"
            // Resolver may fall back to the shortest available window when a
            // legacy or provider-specific id is missing. Label the value with
            // the actual window represented by the cached quota snapshot.
            val periodId = balance.quota?.find(quotaPeriod)?.id
                ?: balance.quota?.ordered()?.firstOrNull()?.id
                ?: quotaPeriod
            val periodLabel = when (quotaPeriodRank(periodId)) {
                0 -> context.getString(R.string.widget_subscription_5h)
                1 -> context.getString(R.string.widget_subscription_weekly)
                2 -> context.getString(R.string.widget_subscription_monthly)
                else -> context.getString(R.string.widget_subscription_5h)
            }
            return context.getString(R.string.widget_subscription_primary, periodLabel, percentage)
        }
        val first = formatCurrencyDisplay(formatter, balance.totalBalance, balance.currency, compact)
        val second = balance.totalBalance2.takeIf { it.isNotEmpty() && (it.toDoubleOrNull() ?: 0.0) > 0 }
            ?.let { " · ${formatCurrencyDisplay(formatter, it, balance.currency2, compact)}" }
        return first + (second ?: "")
    }

    private fun formatCurrencyDisplay(
        formatter: LocalizedFormatter,
        amount: String,
        currency: String,
        compact: Boolean
    ): String {
        val value = amount.toDoubleOrNull() ?: 0.0
        return if (compact) {
            FormatUtils.currencySymbol(currency) + formatter.formatAmount(value)
        } else {
            formatter.formatCurrency(value, currency)
        }
    }

    private fun formatSignedValue(formatter: LocalizedFormatter, value: String, currency: String): String =
        formatter.formatCurrency(value.toDoubleOrNull() ?: 0.0, currency)

}
