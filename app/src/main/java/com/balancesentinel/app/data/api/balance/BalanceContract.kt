package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Request

interface BalanceContract {
    val type: BalanceProviderType
    val endpoint: HttpUrl

    fun request(apiKey: String, endpoint: HttpUrl = this.endpoint): Request =
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

    fun parse(
        body: String,
        providerType: ProviderType,
        accountId: String
    ): ProviderResult<UnifiedBalance>
}

internal fun JsonObject.requiredFiniteDouble(name: String): Double {
    val primitive = this[name]?.jsonPrimitive
        ?: throw SerializationException("missing amount field: $name")
    val value = primitive.content.toDoubleOrNull()
        ?: throw SerializationException("invalid amount field: $name")
    if (!value.isFinite()) {
        throw SerializationException("non-finite amount field: $name")
    }
    return value
}

internal fun JsonObject.optionalFiniteDouble(name: String): Double? =
    if (containsKey(name)) requiredFiniteDouble(name) else null
