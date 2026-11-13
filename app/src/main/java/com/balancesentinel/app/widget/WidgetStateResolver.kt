package com.balancesentinel.app.widget

import com.balancesentinel.app.data.api.PERCENTAGE_CURRENCY
import com.balancesentinel.app.data.refresh.RefreshBatchState

object WidgetStateResolver {
    private val FAILED_BATCH_STATES = setOf(
        RefreshBatchState.PARTIAL,
        RefreshBatchState.FAILED,
        RefreshBatchState.CANCELLED
    )

    fun resolve(input: WidgetStateInput): WidgetViewState {
        val config = input.config ?: return WidgetViewState.Unconfigured(
            reason = WidgetViewState.Unconfigured.Reason.MISSING_CONFIG
        )
        val isTotal = config.accountId == WidgetConfig.TOTAL_ACCOUNT_ID
        if (!isTotal && config.accountId !in input.activeAccounts) {
            return WidgetViewState.Unconfigured(
                reason = WidgetViewState.Unconfigured.Reason.ACCOUNT_REMOVED
            )
        }

        val candidates = if (isTotal) {
            input.balances.filter { it.accountId in input.activeAccounts }
        } else {
            input.balances.filter {
                it.accountId == config.accountId && it.currency == config.currency
            }
        }
        val selection = WidgetSelection(
            accountId = config.accountId.takeUnless { isTotal },
            currency = config.currency.takeUnless { isTotal },
            label = if (isTotal) null else input.activeAccounts[config.accountId]
                ?: candidates.firstOrNull()?.label,
            accountCount = candidates.map { it.accountId }.toSet().size,
            total = isTotal,
            quotaPeriod = config.quotaPeriod
        )
        val aggregate = when {
            candidates.isEmpty() -> null
            config.currency == PERCENTAGE_CURRENCY -> aggregateSubscription(candidates, config.quotaPeriod)
            else -> BalanceWidgetDataStore.aggregateTopTwo(candidates)
        }
        if (input.capabilityRestricted) {
            return WidgetViewState.PermissionRestricted(selection, aggregate)
        }
        val affectedByBatchFailure = isAccountAffectedByBatchFailure(input.lastRefresh, config, isTotal)
        if (aggregate == null) {
            return if (affectedByBatchFailure) {
                WidgetViewState.RefreshFailed(selection)
            } else {
                WidgetViewState.NoData(selection)
            }
        }
        // A widget may only report staleness for the accounts it actually shows: one failing
        // account elsewhere in the batch must not paint every other widget as out of date.
        val hasStaleData = candidates.any { it.stale }
        return if (hasStaleData || affectedByBatchFailure) {
            WidgetViewState.Stale(selection, aggregate, input.lastRefresh?.state)
        } else {
            WidgetViewState.Fresh(selection, aggregate)
        }
    }

    /**
     * True when the last batch verdict concerns this widget. A single-account widget follows its
     * own account; a total widget only follows verdicts that concern every account it aggregates.
     */
    private fun isAccountAffectedByBatchFailure(
        status: WidgetRefreshStatus?,
        config: WidgetConfig,
        isTotal: Boolean
    ): Boolean {
        if (status == null) return false
        val batchFailed = status.state in FAILED_BATCH_STATES
        if (!isTotal) {
            // Older payloads carry no per-account detail; then the widget only knows the batch.
            val failedAccounts = status.failedAccountIds
            return if (failedAccounts.isEmpty()) batchFailed else config.accountId in failedAccounts
        }
        return batchFailed && status.failureCount >= status.accountCount
    }

    /** Subscription percentages are not monetary amounts and cannot use currency aggregation. */
    private fun aggregateSubscription(
        candidates: List<AccountBalance>,
        requestedPeriod: String
    ): AggregatedBalance? {
        val subscriptions = candidates
            .filter { it.currency == PERCENTAGE_CURRENCY }
            .sortedByDescending { it.lastUpdated }
        val primary = subscriptions.firstOrNull() ?: return null
        val selectedPeriod = primary.quota
            ?.find(requestedPeriod)
            ?: primary.quota?.ordered()?.firstOrNull()
        val used = selectedPeriod
            ?.usedPercent
            ?: primary.totalBalance.toDoubleOrNull()?.let { remaining -> 100.0 - remaining }
            ?: return null
        return AggregatedBalance(
            totalBalance = used.coerceIn(0.0, 100.0).toString(),
            currency = PERCENTAGE_CURRENCY,
            isAvailable = subscriptions.all { it.isAvailable && !it.stale },
            grantedBalance = "",
            toppedUpBalance = "",
            accountCount = subscriptions.map { it.accountId }.distinct().size,
            lastUpdated = subscriptions.maxOf { it.lastUpdated },
            quota = primary.quota
        )
    }
}
