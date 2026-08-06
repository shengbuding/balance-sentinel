package com.balancesentinel.app.data.migration

import kotlinx.serialization.Serializable

@Serializable
data class LegacyAccountMapping(
    val legacyStorageId: String,
    val accountId: String,
    val credentialGeneration: String
)
