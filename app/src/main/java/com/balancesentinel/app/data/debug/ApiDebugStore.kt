package com.balancesentinel.app.data.debug

/**
 * API调试条目数据模型
 * 记录完整的请求和响应信息
 */
data class ApiDebugEntry(
    val accountId: String,
    val url: String,
    val method: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val statusCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String,
    val timestamp: Long,
    val duration: Long, // 请求耗时（毫秒）
    val error: String? = null, // 错误信息（如果有）
    // 新增字段
    val accountLabel: String? = null, // 账户标签
    val providerType: String? = null, // 供应商类型
    val baseUrl: String? = null, // 基础URL
    val endpoint: String? = null, // API端点
    val isCustomScript: Boolean = false, // 是否使用自定义脚本
    val scriptPreview: String? = null, // 脚本预览（前100字符）
    val exceptionType: String? = null, // 异常类型
    val exceptionStack: String? = null // 异常堆栈（前500字符）
)

/**
 * API调试数据存储（单例）
 * 按账户ID分组存储调试条目
 */
object ApiDebugStore {
    private val entries = mutableMapOf<String, MutableList<ApiDebugEntry>>()

    /**
     * 添加调试条目
     */
    @Synchronized
    fun addEntry(entry: ApiDebugEntry) {
        val accountEntries = entries.getOrPut(entry.accountId) { mutableListOf() }
        accountEntries.add(entry)

        // 限制每个账户最多保留50条记录
        if (accountEntries.size > 50) {
            accountEntries.removeFirst()
        }
    }

    /**
     * 获取指定账户的调试条目
     */
    @Synchronized
    fun getEntries(accountId: String): List<ApiDebugEntry> {
        return entries[accountId]?.toList() ?: emptyList()
    }

    /**
     * 清空指定账户的调试条目
     */
    @Synchronized
    fun clearEntries(accountId: String) {
        entries.remove(accountId)
    }

    /**
     * 清空所有调试条目
     */
    @Synchronized
    fun clearAll() {
        entries.clear()
    }

    /**
     * 获取所有账户ID
     */
    @Synchronized
    fun getAccountIds(): Set<String> {
        return entries.keys.toSet()
    }
}