package com.balancesentinel.app.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import com.balancesentinel.app.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.ui.CustomIcons
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

// ═══════════════════════════════════════════════════════════
// 平台配置
// ═══════════════════════════════════════════════════════════

/**
 * 控制台平台配置
 */
data class ConsolePlatform(
    val id: String,
    val name: String,
    val loginUrl: String,
    val dashboardUrl: String,
    val successUrlPatterns: List<String>,
    val loginPagePatterns: List<String> = listOf("/sign_in", "/login", "/register")
) {
    fun isLoginSuccess(url: String): Boolean {
        if (loginPagePatterns.any { url.contains(it) }) return false
        return successUrlPatterns.any { url.contains(it) }
    }
}

/** 预定义平台 */
object ConsolePlatforms {
    val DEEPSEEK = ConsolePlatform(
        id = "deepseek",
        name = "DeepSeek",
        loginUrl = "https://platform.deepseek.com/sign_in",
        dashboardUrl = "https://platform.deepseek.com/overview",
        successUrlPatterns = listOf(
            "platform.deepseek.com/overview",
            "platform.deepseek.com/dashboard",
            "platform.deepseek.com/usage",
            "platform.deepseek.com/billing"
        )
    )

    val MIMO = ConsolePlatform(
        id = "mimo",
        name = "Xiaomi MiMo",
        loginUrl = "https://platform.xiaomimimo.com/sign_in",
        dashboardUrl = "https://platform.xiaomimimo.com/#/console/usage",
        successUrlPatterns = listOf(
            "platform.xiaomimimo.com/#/console",
            "platform.xiaomimimo.com/console",
            "platform.xiaomimimo.com/#/overview"
        )
    )
}

// ═══════════════════════════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════════════════════════

/**
 * API 日志条目
 */
data class ApiLogEntry(
    val url: String,
    val method: String = "GET",
    val statusCode: Int = 0,
    val responseBody: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════
// WebView 工具函数
// ═══════════════════════════════════════════════════════════

/** 允许拦截的域名白名单 */
private val ALLOWED_DOMAINS = listOf(
    "platform.deepseek.com",
    "api.deepseek.com",
    "platform.xiaomimimo.com",
    "api.xiaomimimo.com"
)

/**
 * 拦截 API 请求并获取完整响应（同步版本，仅在 WebView 线程中使用）
 * @return WebResourceResponse 或 null（交给默认处理）
 */
fun interceptApiRequest(
    request: WebResourceRequest?,
    apiLogs: MutableList<ApiLogEntry>,
    tag: String
): WebResourceResponse? {
    val reqUrl = request?.url?.toString() ?: ""
    val method = request?.method ?: "GET"

    // 只拦截白名单域名的 API 请求
    if (!reqUrl.contains("/api/") || ALLOWED_DOMAINS.none { reqUrl.contains(it) }) {
        return null
    }

    DebugLogger.log("[$tag] API: $method $reqUrl")

    return try {
        val connection = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000  // 缩短超时时间
        connection.readTimeout = 5000

        // 复制请求头
        request?.requestHeaders?.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        // 添加 Cookie
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(reqUrl)
        if (cookies != null) {
            connection.setRequestProperty("Cookie", cookies)
        }

        connection.connect()

        val statusCode = connection.responseCode
        val inputStream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        // 读取完整响应（处理 gzip）
        val responseBody = try {
            val encoding = connection.contentEncoding
            val bytes = if (encoding != null && encoding.contains("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(inputStream).readBytes()
            } else {
                inputStream?.readBytes() ?: ByteArray(0)
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            inputStream?.readBytes()?.let { String(it, Charsets.UTF_8) } ?: ""
        }

        // 仅在 Debug 构建中记录完整响应
        val entry = if (BuildConfig.DEBUG) {
            ApiLogEntry(
                url = reqUrl,
                method = method,
                statusCode = statusCode,
                responseBody = responseBody
            )
        } else {
            // Release 版本只记录元数据
            ApiLogEntry(
                url = reqUrl,
                method = method,
                statusCode = statusCode,
                responseBody = ""
            )
        }
        synchronized(apiLogs) {
            apiLogs.removeAll { it.url == reqUrl }
            apiLogs.add(entry)
            if (apiLogs.size > 50) apiLogs.removeFirst()
        }

        val contentType = connection.contentType ?: "application/json"
        val encoding = connection.contentEncoding ?: "utf-8"

        WebResourceResponse(
            contentType,
            encoding,
            statusCode,
            connection.responseMessage,
            connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") },
            responseBody.byteInputStream()
        )
    } catch (e: Exception) {
        DebugLogger.log("[$tag] Error: ${e.message}")
        null
    }
}

// ═══════════════════════════════════════════════════════════
// 调试面板 UI
// ═══════════════════════════════════════════════════════════

/**
 * API 调试面板（通用组件）
 */
@Composable
fun ApiDebugPanel(
    apiLogs: List<ApiLogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var expandedIndex by remember { mutableIntStateOf(-1) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "API 调试 (${apiLogs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    // 复制全部
                    IconButton(
                        onClick = { copyAllToClipboard(context, apiLogs) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(CustomIcons.ContentCopy, "复制", modifier = Modifier.size(18.dp))
                    }
                    // 保存文件
                    IconButton(
                        onClick = { saveToFile(context, apiLogs) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(CustomIcons.SaveAlt, "保存", modifier = Modifier.size(18.dp))
                    }
                    // 清空
                    IconButton(
                        onClick = {
                            onClear()
                            Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Delete, "清空", modifier = Modifier.size(18.dp))
                    }
                    // 关闭
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // API 日志列表
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                if (apiLogs.isEmpty()) {
                    Text(
                        text = "暂无 API 请求\n浏览控制台页面会自动记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    apiLogs.forEachIndexed { index, log ->
                        ApiLogItem(
                            log = log,
                            isExpanded = expandedIndex == index,
                            onToggleExpand = {
                                expandedIndex = if (expandedIndex == index) -1 else index
                            },
                            onCopy = { copySingleToClipboard(context, log) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条 API 日志
 */
@Composable
private fun ApiLogItem(
    log: ApiLogEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // URL 行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = log.url.substringAfter("/api/"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = log.method,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${log.statusCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (log.statusCode == 200)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(CustomIcons.ContentCopy, "复制", modifier = Modifier.size(14.dp))
                }
            }

            // 展开的 JSON
            if (isExpanded && log.responseBody.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatJson(log.responseBody),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 共享 UI 组件
// ═══════════════════════════════════════════════════════════

/**
 * 控制台通用 TopAppBar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onToggleDebug: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        },
        actions = {
            // 调试按钮仅在 Debug 构建中可见
            if (BuildConfig.DEBUG) {
                IconButton(onClick = onToggleDebug) {
                    Icon(Icons.Filled.Settings, "调试")
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "刷新")
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.Filled.Close, "退出")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * 控制台通用 WebView
 */
@Composable
fun ConsoleWebView(
    url: String,
    onUrlChange: (String) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onApiRequest: (WebResourceRequest?) -> WebResourceResponse?,
    webView: (WebView) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                settings.userAgentString = DEFAULT_USER_AGENT

                val currentWebView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(currentWebView, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChange(true)
                        url?.let { onUrlChange(it) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChange(false)
                        url?.let { onUrlChange(it) }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return onApiRequest(request) ?: super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = WebChromeClient()

                webView(this)
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

/**
 * 格式化 JSON
 */
fun formatJson(json: String): String {
    return try {
        org.json.JSONObject(json).toString(2)
    } catch (e: Exception) {
        try {
            org.json.JSONArray(json).toString(2)
        } catch (e2: Exception) {
            json
        }
    }
}

/**
 * 复制全部日志到剪贴板
 */
private fun copyAllToClipboard(context: Context, apiLogs: List<ApiLogEntry>) {
    val text = apiLogs.joinToString("\n\n") { log ->
        buildString {
            appendLine("${log.method} ${log.url}")
            appendLine("Status: ${log.statusCode}")
            appendLine("Response:")
            appendLine(log.responseBody)
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("api_logs", text))
    Toast.makeText(context, "已复制全部", Toast.LENGTH_SHORT).show()
}

/**
 * 复制单条日志到剪贴板
 */
private fun copySingleToClipboard(context: Context, log: ApiLogEntry) {
    val text = buildString {
        appendLine("${log.method} ${log.url}")
        appendLine("Status: ${log.statusCode}")
        appendLine("Response:")
        appendLine(log.responseBody)
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("api_log", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/**
 * 保存日志到应用内部存储（安全）
 */
private fun saveToFile(context: Context, apiLogs: List<ApiLogEntry>) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "api_logs_$timestamp.json"
        // 使用应用内部存储，其他应用无法访问
        val logsDir = File(context.filesDir, "console_logs")
        logsDir.mkdirs()
        val file = File(logsDir, fileName)

        val content = buildString {
            appendLine("[")
            apiLogs.forEachIndexed { index, log ->
                appendLine("  {")
                appendLine("    \"url\": \"${log.url}\",")
                appendLine("    \"method\": \"${log.method}\",")
                appendLine("    \"statusCode\": ${log.statusCode},")
                append("    \"response\": ")
                // 安全转义 JSON 字符串
                append(org.json.JSONObject.quote(log.responseBody))
                appendLine()
                append(if (index < apiLogs.size - 1) "," else "")
                appendLine()
                appendLine("  }")
            }
            appendLine("]")
        }

        file.writeText(content)
        Toast.makeText(context, "已保存到: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
