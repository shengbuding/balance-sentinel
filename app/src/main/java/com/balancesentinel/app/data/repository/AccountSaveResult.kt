package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.AccountInfo

sealed interface AccountSaveResult {
    data class Created(val account: AccountInfo) : AccountSaveResult
    data class Updated(val before: AccountInfo, val account: AccountInfo) : AccountSaveResult
    data class Replaced(val before: AccountInfo, val account: AccountInfo) : AccountSaveResult
}
