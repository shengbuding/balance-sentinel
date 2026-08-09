package com.balancesentinel.app.data.refresh

/** Terminal state of a multi-account refresh run. */
enum class RefreshBatchState {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED
}

/** Aggregate derived from the terminal per-account outcomes. */
data class RefreshBatchAggregate(
    val state: RefreshBatchState,
    val accountCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val cancelledCount: Int
)

/** Durable run id plus the complete per-account result set and aggregate. */
data class RefreshBatchResult(
    val runId: String,
    val results: List<AccountRefreshResult>,
    val aggregate: RefreshBatchAggregate
)

internal fun deriveRefreshBatchAggregate(results: List<AccountRefreshResult>): RefreshBatchAggregate {
    val successCount = results.count { it is AccountRefreshResult.Committed }
    val cancelledCount = 0
    val failureCount = results.size - successCount - cancelledCount
    val state = when {
        results.isEmpty() -> RefreshBatchState.FAILED
        successCount == results.size -> RefreshBatchState.SUCCEEDED
        cancelledCount == results.size -> RefreshBatchState.CANCELLED
        successCount > 0 -> RefreshBatchState.PARTIAL
        else -> RefreshBatchState.FAILED
    }
    return RefreshBatchAggregate(state, results.size, successCount, failureCount, cancelledCount)
}
