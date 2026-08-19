package com.balancesentinel.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DeepSeek /user/balance API 响应体
 */
@Serializable
data class BalanceResponse(
    @SerialName("is_available")
    val isAvailable: Boolean,
    @SerialName("balance_infos")
    val balanceInfos: List<BalanceInfo> = emptyList()
)

@Serializable
data class BalanceInfo(
    val currency: String,
    @SerialName("total_balance")
    val totalBalance: String,
    @SerialName("granted_balance")
    val grantedBalance: String,
    @SerialName("topped_up_balance")
    val toppedUpBalance: String,
    val displayFields: Map<String, String> = emptyMap(),
    val quota: com.balancesentinel.app.data.api.QuotaSnapshot? = null
)
