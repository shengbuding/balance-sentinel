package com.balancesentinel.app.service

import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.data.refresh.AccountStoreRead

data class ServiceRefreshBatch(
    val accountCount: Int,
    val committedBalances: List<AccountBalance>
)

fun interface ServiceAccountSnapshotReader {
    suspend fun read(): AccountStoreRead
}

interface RefreshDeadlineLifecycle {
    fun markStarted()
    fun clear()

    data object None : RefreshDeadlineLifecycle {
        override fun markStarted() = Unit
        override fun clear() = Unit
    }
}

/**
 * Production runner used by [BalanceRefreshService] to route refreshes
 * through the shared [RefreshGateway] and read committed Widget storage
 * for notification derivation.
 *
 * The gateway's committer owns all persistence; this runner only triggers
 * the refresh and reads the committed results. It does not duplicate
 * committer writes.
 */
class BalanceRefreshRunner(
    private val gateway: RefreshGateway,
    private val refreshDeadline: RefreshDeadlineLifecycle = RefreshDeadlineLifecycle.None,
    private val accountSnapshotReader: ServiceAccountSnapshotReader? = null,
    private val committedBalanceReader: () -> List<AccountBalance>
) {
    suspend fun refreshBatch(): ServiceRefreshBatch {
        refreshDeadline.markStarted()
        return try {
            gateway.refreshAll(RefreshTrigger.SERVICE)
            ServiceRefreshBatch(0, committedBalanceReader())
        } finally {
            refreshDeadline.clear()
        }
    }
    /**
     * Refresh all accounts via the shared gateway with [RefreshTrigger.SERVICE],
     * then read committed Widget storage for notification derivation.
     */
    suspend fun refreshAndReadCommitted(): List<AccountBalance> {
        refreshDeadline.markStarted()
        return try {
            gateway.refreshAll(RefreshTrigger.SERVICE)
            committedBalanceReader()
        } finally {
            refreshDeadline.clear()
        }
    }
}
