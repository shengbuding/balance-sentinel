package com.balancesentinel.app.ui.viewmodel

sealed interface HomeUiEvent {
    data class ShowError(
        val accountId: String?,
        val message: String
    ) : HomeUiEvent
}
