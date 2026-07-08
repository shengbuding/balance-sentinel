package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.api.DeepSeekApiService
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.UsageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 余额数据仓库 — 封装 API 调用，所有网络请求切换到 IO 线程执行。
 */
class BalanceRepository(
    private val apiService: DeepSeekApiService = DeepSeekApiService()
) {
    /**
     * 查询余额 — 在 IO 线程执行网络请求。
     */
    suspend fun fetchBalance(apiKey: String): Result<BalanceResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getBalance(apiKey)
                Result.success(response)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(
                    IOException("网络错误: ${e.javaClass.simpleName} — ${e.message ?: "无详情"}", e)
                )
            }
        }
    }

    /**
     * 查询用量统计 — 在 IO 线程执行。
     */
    suspend fun fetchUsage(apiKey: String): Result<UsageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getUsage(apiKey)
                Result.success(response)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(IOException("用量查询失败: ${e.message}"))
            }
        }
    }
}
