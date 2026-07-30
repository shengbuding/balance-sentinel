package com.balancesentinel.app.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.balancesentinel.app.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null
)

/**
 * 会话调试信息
 */
data class SessionDebugInfo(
    val platformId: String,
    val platformName: String,
    val isLoggedIn: Boolean,
    val isSessionValid: Boolean,
    val cookieCount: Int,
    val localStorageCount: Int,
    val email: String?,
    val currentUrl: String?,
    val cookies: Map<String, String>,
    val localStorage: Map<String, String>,
    val sessionCreatedAt: String?,
    val sessionExpiresAt: String?
)

// ═══════════════════════════════════════════════════════════
// 调试面板
// ═══════════════════════════════════════════════════════════

@Composable
fun ApiDebugPanel(
    apiLogs: List<ApiLogEntry>,
    sessionInfo: SessionDebugInfo? = null,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onLogout: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "调试面板",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row {
                    // 复制全部调试信息
                    IconButton(
                        onClick = { copyDebugInfoToClipboard(context, apiLogs, sessionInfo) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(CustomIcons.ContentCopy, "复制全部", modifier = Modifier.size(18.dp))
                    }
                    // 保存文件
                    IconButton(
                        onClick = { saveToFile(context, apiLogs, sessionInfo) },
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

            Spacer(modifier = Modifier.height(12.dp))

            // Tab 切换
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("API 请求 (${apiLogs.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("会话状态") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("调试日志") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 内容区域
            when (selectedTab) {
                0 -> ApiLogsTab(apiLogs)
                1 -> SessionInfoTab(sessionInfo, onLogout)
                2 -> DebugLogsTab()
            }
        }
    }
}

@Composable
private fun ApiLogsTab(apiLogs: List<ApiLogEntry>) {
    val context = LocalContext.current

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
            apiLogs.forEach { log ->
                ApiLogItem(
                    log = log,
                    onCopy = { copySingleLogToClipboard(context, log) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SessionInfoTab(sessionInfo: SessionDebugInfo?, onLogout: (() -> Unit)?) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (sessionInfo == null) {
            Text(
                text = "无会话信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 会话状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "会话状态",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DebugInfoRow("平台", sessionInfo.platformName)
                    DebugInfoRow("平台 ID", sessionInfo.platformId)
                    DebugInfoRow("登录状态", if (sessionInfo.isLoggedIn) "✅ 已登录" else "❌ 未登录")
                    DebugInfoRow("Session 有效性", if (sessionInfo.isSessionValid) "✅ 有效" else "❌ 无效/已过期")
                    DebugInfoRow("Cookies 数量", "${sessionInfo.cookieCount}")
                    DebugInfoRow("LocalStorage 数量", "${sessionInfo.localStorageCount}")
                    DebugInfoRow("邮箱", sessionInfo.email ?: "未记录")
                    DebugInfoRow("当前 URL", sessionInfo.currentUrl ?: "无")
                    DebugInfoRow("Session 创建时间", sessionInfo.sessionCreatedAt ?: "未知")
                    DebugInfoRow("Session 过期时间", sessionInfo.sessionExpiresAt ?: "未知")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cookies 详情
            if (sessionInfo.cookies.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cookies (${sessionInfo.cookies.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    val text = sessionInfo.cookies.entries.joinToString("\n") { "${it.key}=${it.value}" }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Cookies", text)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "已复制 Cookies", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(CustomIcons.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        sessionInfo.cookies.forEach { (key, value) ->
                            Text(
                                text = "$key = $value",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // LocalStorage 详情
            if (sessionInfo.localStorage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LocalStorage (${sessionInfo.localStorage.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    val text = sessionInfo.localStorage.entries.joinToString("\n") { "${it.key}=${it.value}" }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("LocalStorage", text)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "已复制 LocalStorage", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(CustomIcons.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        sessionInfo.localStorage.forEach { (key, value) ->
                            Text(
                                text = "$key = ${value.take(100)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 操作按钮
            if (onLogout != null) {
                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("重新登录")
                }
            }
        }
    }
}

@Composable
private fun DebugLogsTab() {
    val context = LocalContext.current
    val debugLogs = remember { DebugLogger.getLogs() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "调试日志 (${debugLogs.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = {
                    val text = debugLogs.joinToString("\n")
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Debug Logs", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制调试日志", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(CustomIcons.ContentCopy, "复制", modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (debugLogs.isEmpty()) {
            Text(
                text = "暂无调试日志",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            debugLogs.forEach { log ->
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun DebugInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ApiLogItem(log: ApiLogEntry, onCopy: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // URL 和状态码
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = log.method,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${log.statusCode}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (log.statusCode) {
                            in 200..299 -> MaterialTheme.colorScheme.primary
                            in 400..499 -> MaterialTheme.colorScheme.error
                            in 500..599 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (log.error != null) {
                        Text(
                            text = "ERROR",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(CustomIcons.ContentCopy, "复制", modifier = Modifier.size(14.dp))
                    }
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // URL
            Text(
                text = log.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 2
            )

            // 时间戳
            Text(
                text = formatTimestamp(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 展开时显示详细信息
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                // 错误信息
                if (log.error != null) {
                    Text(
                        text = "错误: ${log.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // 请求头
                if (log.requestHeaders.isNotEmpty()) {
                    Text(
                        text = "请求头:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    log.requestHeaders.forEach { (key, value) ->
                        Text(
                            text = "  $key: $value",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // 响应头
                if (log.responseHeaders.isNotEmpty()) {
                    Text(
                        text = "响应头:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    log.responseHeaders.forEach { (key, value) ->
                        Text(
                            text = "  $key: $value",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // 响应体
                if (log.responseBody.isNotBlank()) {
                    Text(
                        text = "响应体:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.responseBody,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return sdf.format(Date(timestamp))
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

private fun copyDebugInfoToClipboard(context: Context, apiLogs: List<ApiLogEntry>, sessionInfo: SessionDebugInfo?) {
    val content = buildString {
        appendLine("=== 钱包哨兵调试信息 ===")
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine()

        // 会话信息
        if (sessionInfo != null) {
            appendLine("=== 会话状态 ===")
            appendLine("平台: ${sessionInfo.platformName} (${sessionInfo.platformId})")
            appendLine("登录状态: ${if (sessionInfo.isLoggedIn) "已登录" else "未登录"}")
            appendLine("Session有效性: ${if (sessionInfo.isSessionValid) "有效" else "无效/已过期"}")
            appendLine("Cookies数量: ${sessionInfo.cookieCount}")
            appendLine("LocalStorage数量: ${sessionInfo.localStorageCount}")
            appendLine("邮箱: ${sessionInfo.email ?: "未记录"}")
            appendLine("当前URL: ${sessionInfo.currentUrl ?: "无"}")
            appendLine("Session创建时间: ${sessionInfo.sessionCreatedAt ?: "未知"}")
            appendLine("Session过期时间: ${sessionInfo.sessionExpiresAt ?: "未知"}")
            appendLine()

            if (sessionInfo.cookies.isNotEmpty()) {
                appendLine("=== Cookies ===")
                sessionInfo.cookies.forEach { (key, value) ->
                    appendLine("$key = $value")
                }
                appendLine()
            }

            if (sessionInfo.localStorage.isNotEmpty()) {
                appendLine("=== LocalStorage ===")
                sessionInfo.localStorage.forEach { (key, value) ->
                    appendLine("$key = $value")
                }
                appendLine()
            }
        }

        // API 请求日志
        appendLine("=== API 请求日志 (${apiLogs.size}) ===")
        apiLogs.forEachIndexed { index, log ->
            appendLine("--- 请求 #${index + 1} ---")
            appendLine("时间: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(log.timestamp))}")
            appendLine("方法: ${log.method}")
            appendLine("URL: ${log.url}")
            appendLine("状态码: ${log.statusCode}")
            if (log.error != null) {
                appendLine("错误: ${log.error}")
            }
            if (log.requestHeaders.isNotEmpty()) {
                appendLine("请求头:")
                log.requestHeaders.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
            }
            if (log.responseHeaders.isNotEmpty()) {
                appendLine("响应头:")
                log.responseHeaders.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
            }
            if (log.responseBody.isNotBlank()) {
                appendLine("响应体:")
                appendLine(log.responseBody)
            }
            appendLine()
        }

        // 调试日志
        val debugLogs = DebugLogger.getLogs()
        if (debugLogs.isNotEmpty()) {
            appendLine("=== 调试日志 (${debugLogs.size}) ===")
            debugLogs.forEach { log ->
                appendLine(log)
            }
        }
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("调试信息", content)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制全部调试信息到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun copySingleLogToClipboard(context: Context, log: ApiLogEntry) {
    val content = buildString {
        appendLine("方法: ${log.method}")
        appendLine("URL: ${log.url}")
        appendLine("状态码: ${log.statusCode}")
        if (log.error != null) {
            appendLine("错误: ${log.error}")
        }
        if (log.requestHeaders.isNotEmpty()) {
            appendLine("请求头:")
            log.requestHeaders.forEach { (key, value) ->
                appendLine("  $key: $value")
            }
        }
        if (log.responseHeaders.isNotEmpty()) {
            appendLine("响应头:")
            log.responseHeaders.forEach { (key, value) ->
                appendLine("  $key: $value")
            }
        }
        if (log.responseBody.isNotBlank()) {
            appendLine("响应体:")
            appendLine(log.responseBody)
        }
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API 日志", content)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun saveToFile(context: Context, apiLogs: List<ApiLogEntry>, sessionInfo: SessionDebugInfo? = null) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "console_debug_$timestamp.json"

        // 获取调试日志
        val debugLogs = DebugLogger.getLogs()

        val content = buildString {
            appendLine("{")
            appendLine("  \"timestamp\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\",")

            // 会话信息
            if (sessionInfo != null) {
                appendLine("  \"session\": {")
                appendLine("    \"platform_id\": \"${sessionInfo.platformId}\",")
                appendLine("    \"platform_name\": \"${sessionInfo.platformName}\",")
                appendLine("    \"is_logged_in\": ${sessionInfo.isLoggedIn},")
                appendLine("    \"is_session_valid\": ${sessionInfo.isSessionValid},")
                appendLine("    \"cookie_count\": ${sessionInfo.cookieCount},")
                appendLine("    \"local_storage_count\": ${sessionInfo.localStorageCount},")
                appendLine("    \"email\": ${org.json.JSONObject.quote(sessionInfo.email ?: "")},")
                appendLine("    \"current_url\": ${org.json.JSONObject.quote(sessionInfo.currentUrl ?: "")},")
                appendLine("    \"session_created_at\": ${org.json.JSONObject.quote(sessionInfo.sessionCreatedAt ?: "")},")
                appendLine("    \"session_expires_at\": ${org.json.JSONObject.quote(sessionInfo.sessionExpiresAt ?: "")},")
                appendLine("    \"cookies\": {")
                sessionInfo.cookies.entries.forEachIndexed { index, (key, value) ->
                    append("      ${org.json.JSONObject.quote(key)}: ${org.json.JSONObject.quote(value)}")
                    if (index < sessionInfo.cookies.size - 1) appendLine(",") else appendLine()
                }
                appendLine("    },")
                appendLine("    \"local_storage\": {")
                sessionInfo.localStorage.entries.forEachIndexed { index, (key, value) ->
                    append("      ${org.json.JSONObject.quote(key)}: ${org.json.JSONObject.quote(value)}")
                    if (index < sessionInfo.localStorage.size - 1) appendLine(",") else appendLine()
                }
                appendLine("    }")
                appendLine("  },")
            }

            // API 请求日志
            appendLine("  \"api_logs\": [")
            apiLogs.forEachIndexed { index, log ->
                appendLine("    {")
                appendLine("      \"timestamp\": \"${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(log.timestamp))}\",")
                appendLine("      \"url\": ${org.json.JSONObject.quote(log.url)},")
                appendLine("      \"method\": ${org.json.JSONObject.quote(log.method)},")
                appendLine("      \"statusCode\": ${log.statusCode},")
                if (log.error != null) {
                    appendLine("      \"error\": ${org.json.JSONObject.quote(log.error)},")
                }
                append("      \"requestHeaders\": {")
                log.requestHeaders.entries.forEachIndexed { i, (key, value) ->
                    append("${org.json.JSONObject.quote(key)}: ${org.json.JSONObject.quote(value)}")
                    if (i < log.requestHeaders.size - 1) append(", ")
                }
                appendLine("},")
                append("      \"responseHeaders\": {")
                log.responseHeaders.entries.forEachIndexed { i, (key, value) ->
                    append("${org.json.JSONObject.quote(key)}: ${org.json.JSONObject.quote(value)}")
                    if (i < log.responseHeaders.size - 1) append(", ")
                }
                appendLine("},")
                append("      \"responseBody\": ")
                append(org.json.JSONObject.quote(log.responseBody))
                appendLine()
                append(if (index < apiLogs.size - 1) "    }," else "    }")
                appendLine()
            }
            appendLine("  ],")

            // 调试日志
            appendLine("  \"debug_logs\": [")
            debugLogs.forEachIndexed { index, log ->
                append("    ")
                append(org.json.JSONObject.quote(log))
                if (index < debugLogs.size - 1) appendLine(",") else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/WalletSentinel")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            Toast.makeText(context, "已保存到: Download/WalletSentinel/$fileName", Toast.LENGTH_LONG).show()
        } else {
            // 备用方案：保存到应用内部存储
            val file = java.io.File(context.filesDir, fileName)
            file.writeText(content)
            Toast.makeText(context, "已保存到应用内部: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
