package com.balancesentinel.app.data.api

/**
 * 统一供应商操作结果
 */
sealed class ProviderResult<out T> {
    data class Success<T>(val data: T) : ProviderResult<T>()
    data class Failure(val error: ProviderError) : ProviderResult<Nothing>()
}

/**
 * 统一供应商错误类型
 */
sealed class ProviderError(
    val provider: ProviderType,
    val message: String,
    val cause: Throwable? = null
) {
    /**
     * 认证错误（401等）
     */
    class AuthError(
        provider: ProviderType,
        message: String
    ) : ProviderError(provider, message)

    /**
     * 速率限制（429等）
     */
    class RateLimitError(
        provider: ProviderType,
        val retryAfter: Long? = null,
        message: String = "请求过于频繁"
    ) : ProviderError(provider, message)

    /**
     * 网络错误
     */
    class NetworkError(
        provider: ProviderType,
        cause: Throwable
    ) : ProviderError(provider, cause.message ?: "网络错误", cause)

    /**
     * 服务端错误（5xx等）
     */
    class ServerError(
        provider: ProviderType,
        val code: Int,
        message: String
    ) : ProviderError(provider, message)

    /**
     * 配额超限
     */
    class QuotaExceededError(
        provider: ProviderType,
        message: String = "配额已用完"
    ) : ProviderError(provider, message)

    /**
     * 响应解析错误
     */
    class InvalidResponseError(
        provider: ProviderType,
        message: String,
        cause: Throwable? = null
    ) : ProviderError(provider, message, cause)

    /**
     * API不可用
     */
    class ApiUnavailableError(
        provider: ProviderType,
        message: String = "API不可用"
    ) : ProviderError(provider, message)
}

/**
 * 扩展函数：将ProviderResult转换为可空值
 */
fun <T> ProviderResult<T>.getOrNull(): T? {
    return when (this) {
        is ProviderResult.Success -> data
        is ProviderResult.Failure -> null
    }
}

/**
 * 扩展函数：获取数据或抛出异常
 */
fun <T> ProviderResult<T>.getOrThrow(): T {
    return when (this) {
        is ProviderResult.Success -> data
        is ProviderResult.Failure -> throw IllegalStateException(error.message)
    }
}

/**
 * 扩展函数：映射成功结果
 */
fun <T, R> ProviderResult<T>.map(transform: (T) -> R): ProviderResult<R> {
    return when (this) {
        is ProviderResult.Success -> ProviderResult.Success(transform(data))
        is ProviderResult.Failure -> this
    }
}
