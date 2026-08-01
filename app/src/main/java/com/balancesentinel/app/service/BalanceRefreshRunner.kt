package com.balancesentinel.app.service

import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.widget.AccountBalance

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
    private val committedBalanceReader: () -> List<AccountBalance>
) {
    /**
     * Refresh all accounts via the shared gateway with [RefreshTrigger.SERVICE],
     * then read committed Widget storage for notification derivation.
     */
    suspend fun refreshAndReadCommitted(): List<AccountBalance> {
        gateway.refreshAll(RefreshTrigger.SERVICE)
        return committedBalanceReader()
    }
}
