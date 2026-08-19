package com.balancesentinel.app.widget

import com.balancesentinel.app.data.refresh.RefreshBatchState

data class WidgetStateInput(
    val config: WidgetConfig?,
    val activeAccounts: Map<String, String>,
    val balances: List<AccountBalance>,
    val lastRefresh: WidgetRefreshStatus?,
    val capabilityRestricted: Boolean
)

data class WidgetSelection(
    val accountId: String?,
    val currency: String?,
    val label: String?,
    val accountCount: Int = 0,
    val total: Boolean = false,
    val quotaPeriod: String? = null
)

sealed interface WidgetViewState {
    val selection: WidgetSelection

    data class Unconfigured(
        override val selection: WidgetSelection = WidgetSelection(null, null, null),
        val reason: Reason
    ) : WidgetViewState {
        enum class Reason { MISSING_CONFIG, ACCOUNT_REMOVED }
    }

    data class NoData(override val selection: WidgetSelection) : WidgetViewState

    data class Fresh(
        override val selection: WidgetSelection,
        val balance: AggregatedBalance
    ) : WidgetViewState

    data class Stale(
        override val selection: WidgetSelection,
        val balance: AggregatedBalance,
        val refreshState: RefreshBatchState?
    ) : WidgetViewState

    data class PermissionRestricted(
        override val selection: WidgetSelection,
        val balance: AggregatedBalance?
    ) : WidgetViewState

    data class RefreshFailed(override val selection: WidgetSelection) : WidgetViewState
}
