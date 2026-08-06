package com.balancesentinel.app.data.migration

data class LegacyAccountMapping(
    val legacyStorageId: String,
    val accountId: String,
    val credentialGeneration: String
)
