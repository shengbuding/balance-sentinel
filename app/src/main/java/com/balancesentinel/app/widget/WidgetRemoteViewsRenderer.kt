package com.balancesentinel.app.widget

import android.content.Context
import android.widget.RemoteViews
import com.balancesentinel.app.R
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
                formatBalanceDisplay(formatter, it, compact = !expanded)
            }
                ?: context.getString(R.string.widget_query_balance)
            is WidgetViewState.PermissionRestricted -> context.getString(R.string.widget_state_permission_restricted_balance)
            is WidgetViewState.Unconfigured -> context.getString(R.string.widget_state_configure_prompt)
            else -> context.getString(R.string.widget_query_balance)
        }
        val status = when (state) {
            is WidgetViewState.Unconfigured -> context.getString(R.string.widget_state_unconfigured_status)
            is WidgetViewState.NoData -> context.getString(R.string.widget_state_no_data_status)
            is WidgetViewState.Fresh -> if (state.balance.isAvailable) {
                context.getString(R.string.widget_status_available)
            } else context.getString(R.string.widget_status_partial)
            is WidgetViewState.Stale -> context.getString(R.string.widget_state_stale_status)
            is WidgetViewState.PermissionRestricted -> context.getString(R.string.widget_state_permission_restricted_status)
            is WidgetViewState.RefreshFailed -> context.getString(R.string.widget_state_refresh_failed_status)
        }
        val showDetails = expanded && balance != null && state !is WidgetViewState.PermissionRestricted
        val granted = balance?.let {
            context.getString(
                R.string.balance_granted,
                formatSignedValue(formatter, it.grantedBalance, it.currency)
            )
        }.orEmpty()
        val toppedUp = balance?.let {
            context.getString(
                R.string.balance_topped_up,
                formatSignedValue(formatter, it.toppedUpBalance, it.currency)
            )
        }.orEmpty()
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
            views.setViewVisibility(
                R.id.widget_detail_row,
                if (model.showDetails) android.view.View.VISIBLE else android.view.View.GONE
            )
        }
        return views to model
    }

    private fun formatBalanceDisplay(
        formatter: LocalizedFormatter,
        balance: AggregatedBalance,
        compact: Boolean
    ): String {
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
