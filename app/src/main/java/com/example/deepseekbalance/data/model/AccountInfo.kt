package com.example.deepseekbalance.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountInfo(
    val id: String,
    val label: String,
    val apiKey: String
)
