package com.balancesentinel.app.data.debug

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * OkHttp调试拦截器
 * 拦截所有API请求和响应，记录详细信息到ApiDebugStore
 */
class DebugInterceptor(
    private val accountId: String,
    private val accountLabel: String? = null,
    private val providerType: String? = null,
    private val baseUrl: String? = null,
    private val isCustomScript: Boolean = false,
    private val scriptPreview: String? = null
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val url = request.url.toString()

        // 提取端点（去掉baseUrl部分）
        val endpoint = if (baseUrl != null && url.startsWith(baseUrl)) {
            url.removePrefix(baseUrl)
        } else {
            try {
                java.net.URL(url).path
            } catch (e: Exception) {
                url
            }
        }

        // 记录请求头信息
        val requestHeaders = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            val value = request.headers.value(i)
            // 对Authorization头进行掩码处理
            if (name.equals("Authorization", ignoreCase = true)) {
                requestHeaders[name] = maskSensitiveValue(value)
            } else {
                requestHeaders[name] = value
            }
        }

        // 记录请求体（如果有）
        val requestBody = request.body?.let { body ->
            try {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                // 对包含API Key的请求体进行掩码处理
                maskSensitiveJson(bodyStr)
            } catch (e: Exception) {
                "<无法读取请求体: ${e.message}>"
            }
        }

        // 执行请求
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            // 提供更详细的错误信息
            val errorMessage = when (e) {
                is java.net.ConnectException -> "连接失败: ${e.message}"
                is java.net.SocketTimeoutException -> "连接超时: ${e.message}"
                is java.net.UnknownHostException -> "DNS解析失败: ${e.message}"
                is javax.net.ssl.SSLException -> "SSL错误: ${e.message}"
                is java.io.IOException -> "网络错误: ${e.message}"
                else -> "未知错误: ${e.javaClass.simpleName} - ${e.message}"
            }

            // 提取异常信息
            val exceptionType = e.javaClass.simpleName
            val exceptionStack = e.stackTraceToString().take(500)

            // 记录失败的请求
            val entry = ApiDebugEntry(
                accountId = accountId,
                url = url,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                statusCode = 0,
                responseHeaders = emptyMap(),
                responseBody = "",
                timestamp = startTime,
                duration = endTime - startTime,
                error = errorMessage,
                accountLabel = accountLabel,
                providerType = providerType,
                baseUrl = baseUrl,
                endpoint = endpoint,
                isCustomScript = isCustomScript,
                scriptPreview = scriptPreview,
                exceptionType = exceptionType,
                exceptionStack = exceptionStack
            )
            ApiDebugStore.addEntry(entry)
            throw e
        }

        val endTime = System.currentTimeMillis()

        // 记录响应头信息
        val responseHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            responseHeaders[response.headers.name(i)] = response.headers.value(i)
        }

        // 读取响应体
        val responseBody = response.body?.string() ?: ""

        // 创建调试条目
        val entry = ApiDebugEntry(
            accountId = accountId,
            url = url,
            method = request.method,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            statusCode = response.code,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            timestamp = startTime,
            duration = endTime - startTime,
            error = if (!response.isSuccessful) responseBody else null,
            accountLabel = accountLabel,
            providerType = providerType,
            baseUrl = baseUrl,
            endpoint = endpoint,
            isCustomScript = isCustomScript,
            scriptPreview = scriptPreview
        )

        ApiDebugStore.addEntry(entry)

        // 重新构建响应（因为body已被读取）
        return response.newBuilder()
            .body(responseBody.toResponseBody(response.body?.contentType()))
            .build()
    }

    /**
     * 对敏感值进行掩码处理
     * 例如: Bearer sk-1234567890 -> Bearer sk-****567890
     */
    private fun maskSensitiveValue(value: String): String {
        return when {
            value.startsWith("Bearer ", ignoreCase = true) -> {
                val token = value.substring(7)
                if (token.length > 8) {
                    "Bearer ${token.take(4)}****${token.takeLast(4)}"
                } else {
                    "Bearer ****"
                }
            }
            value.length > 8 -> {
                "${value.take(4)}****${value.takeLast(4)}"
            }
            else -> "****"
        }
    }

    /**
     * 对JSON中的敏感字段进行掩码处理
     */
    private fun maskSensitiveJson(json: String): String {
        // 简单处理：对包含apiKey的字段进行掩码
        val pattern = Regex("""("(?:api_?key|apikey|api_?secret|secret|token)"\s*:\s*")([^"]+)(")""", RegexOption.IGNORE_CASE)
        return json.replace(pattern) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val value = matchResult.groupValues[2]
            val suffix = matchResult.groupValues[3]
            if (value.length > 8) {
                "$prefix${value.take(4)}****${value.takeLast(4)}$suffix"
            } else {
                "$prefix****$suffix"
            }
        }
    }
}
