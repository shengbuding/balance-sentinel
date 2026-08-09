package com.balancesentinel.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * RED compatibility interceptor. GREEN wraps the network response source and
 * enforces the encoded-byte budget before OkHttp decompression.
 */
class EncodedResponseLimitInterceptor(
    private val maxBytes: Long,
    private val endpoint: String = "response"
) : Interceptor {
    constructor(budget: ResponseBudget) : this(budget.maxEncodedBytes, budget.endpoint)

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
