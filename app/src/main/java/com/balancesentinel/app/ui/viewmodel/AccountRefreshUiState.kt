package com.balancesentinel.app.ui.viewmodel

/** UI facts for one account refresh generation. */
data class AccountRefreshUiState(
    val requestId: Long = 0L,
    val isLoading: Boolean = false,
    val lastSuccessAt: Long? = null,
    val dataTimestamp: Long? = null,
    val stale: Boolean = false,
    val errorMessage: String? = null
) {
    val error: String?
        get() = errorMessage
}
