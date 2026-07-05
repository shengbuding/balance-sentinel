package com.balancesentinel.app.data.api

import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.UsageResponse
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * DeepSeek API 服务 — 通过 OkHttp 调用 /user/balance
 */
class DeepSeekApiService(
    private val baseUrl: String = "https://api.deepseek.com"
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * GET 请求重试拦截器：仅对幂等 GET 请求在瞬时网络故障或 5xx 时重试。
     * 最大 3 次尝试，指数退避 1s / 2s。
     */
    private class RetryInterceptor : Interceptor {
        companion object {
            private const val MAX_ATTEMPTS = 3
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // 只重试 GET 请求（幂等）
            if (request.method != "GET") return chain.proceed(request)

            var lastException: IOException? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    val response = chain.proceed(request)
                    // 5xx 服务端错误可重试
                    if (response.code in 500..599 && attempt < MAX_ATTEMPTS) {
                        response.close()
                        Logger.w("DeepSeekApi", "Server error ${response.code}, retry $attempt/$MAX_ATTEMPTS")
                        sleepBeforeRetry(attempt)
                        continue
                    }
                    return response
                } catch (e: ConnectException) {
                    lastException = e
                    Logger.w("DeepSeekApi", "Connection refused, retry $attempt/$MAX_ATTEMPTS")
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    Logger.w("DeepSeekApi", "Timeout, retry $attempt/$MAX_ATTEMPTS")
                } catch (e: UnknownHostException) {
                    lastException = e
                    Logger.w("DeepSeekApi", "DNS failure, retry $attempt/$MAX_ATTEMPTS")
                } catch (e: SSLException) {
                    lastException = e
                    Logger.w("DeepSeekApi", "SSL error, retry $attempt/$MAX_ATTEMPTS")
                }
                if (attempt < MAX_ATTEMPTS) sleepBeforeRetry(attempt)
            }
            throw lastException ?: IOException("Retry exhausted")
        }

        private fun sleepBeforeRetry(attempt: Int) {
            try {
                // 指数退避: 1s, 2s
                Thread.sleep(1000L * (1 shl (attempt - 1)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(RetryInterceptor())
        .build()

    /**
     * 查询用户余额。
     * @param apiKey DeepSeek API Key
     * @return BalanceResponse 或 null（网络/认证错误时抛出异常）
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun getBalance(apiKey: String): BalanceResponse {
        val request = Request.Builder()
            .url("$baseUrl/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IOException("Empty response body")

            if (!response.isSuccessful) {
                when (response.code) {
                    401 -> throw IOException("API Key 无效 (401 Unauthorized)")
                    429 -> throw IOException("请求过于频繁，请稍后再试 (429)")
                    else -> throw IOException("API 返回错误 ${response.code}: $body")
                }
            }

            return json.decodeFromString<BalanceResponse>(body)
        }
    }

    /**
     * 查询 API 用量统计。
     * @param apiKey DeepSeek API Key
     * @param startDate 开始日期 "yyyy-MM-dd"，null = 30 天前
     * @param endDate 结束日期 "yyyy-MM-dd"，null = 今天
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun getUsage(apiKey: String, startDate: String? = null, endDate: String? = null): UsageResponse {
        val url = buildString {
            append("$baseUrl/v1/usage")
            if (startDate != null || endDate != null) {
                append("?")
                if (startDate != null) append("start_date=$startDate")
                if (startDate != null && endDate != null) append("&")
                if (endDate != null) append("end_date=$endDate")
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IOException("Empty response body")

            if (!response.isSuccessful) {
                when (response.code) {
                    401 -> throw IOException("API Key 无效 (401)")
                    429 -> throw IOException("请求过于频繁 (429)")
                    else -> throw IOException("API 返回错误 ${response.code}")
                }
            }

            return json.decodeFromString<UsageResponse>(body)
        }
    }
}
