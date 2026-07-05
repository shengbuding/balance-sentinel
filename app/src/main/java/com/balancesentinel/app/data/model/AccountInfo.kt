package com.balancesentinel.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountInfo(
    val id: String,
    val label: String,
    val apiKey: String
)
