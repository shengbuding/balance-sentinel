package com.balancesentinel.app.data.api

import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.UsageResponse
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 服务 — 通过 OkHttp 调用 /user/balance
 */
class DeepSeekApiService {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 查询用户余额。
     * @param apiKey DeepSeek API Key
     * @return BalanceResponse 或 null（网络/认证错误时抛出异常）
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun getBalance(apiKey: String): BalanceResponse {
        val request = Request.Builder()
            .url("https://api.deepseek.com/user/balance")
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
            append("https://api.deepseek.com/v1/usage")
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
