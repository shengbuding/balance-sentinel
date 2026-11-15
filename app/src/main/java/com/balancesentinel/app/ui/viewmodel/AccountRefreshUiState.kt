package com.balancesentinel.app.ui.viewmodel

import com.balancesentinel.app.data.refresh.AuthenticationFailureKind

/** UI facts for one account refresh generation. */
data class AccountRefreshUiState(
    val requestId: Long = 0L,
    val isLoading: Boolean = false,
    val lastSuccessAt: Long? = null,
    val dataTimestamp: Long? = null,
    val stale: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Why an authentication failure happened, when the failure was one. An expired subscription
     * and a rejected key both stop the refresh, but only the first is fixed by renewing, so the
     * card must not paint them the same way.
     */
    val authFailureKind: AuthenticationFailureKind? = null
) {
    val error: String?
        get() = errorMessage

    /** True when the account is still configured correctly and only the plan lapsed. */
    val subscriptionExpired: Boolean
        get() = authFailureKind == AuthenticationFailureKind.SUBSCRIPTION_EXPIRED
}
