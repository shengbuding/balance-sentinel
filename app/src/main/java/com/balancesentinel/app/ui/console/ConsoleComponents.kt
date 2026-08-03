package com.balancesentinel.app.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.DebugReportLabels
import com.balancesentinel.app.data.debug.DebugReportFormatter
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.debugReportLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════════════════════════

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
    val sessionCreatedAt: String?,
    val sessionExpiresAt: String?
)

// ═══════════════════════════════════════════════════════════
// 调试面板
// ═══════════════════════════════════════════════════════════

@Composable
fun ApiDebugPanel(
    apiLogs: List<ApiDebugEntry>,
    sessionInfo: SessionDebugInfo? = null,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onLogout: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val reportLabels = remember(context) { context.debugReportLabels() }
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
                        onClick = {
                            copyDebugInfoToClipboard(context, apiLogs, sessionInfo, reportLabels)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(CustomIcons.ContentCopy, "复制全部", modifier = Modifier.size(18.dp))
                    }
                    // 保存文件
                    IconButton(
                        onClick = { saveToFile(context, apiLogs, sessionInfo, reportLabels) },
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
                0 -> ApiLogsTab(apiLogs, reportLabels)
                1 -> SessionInfoTab(sessionInfo, onLogout)
                2 -> DebugLogsTab()
            }
        }
    }
}

@Composable
private fun ApiLogsTab(
    apiLogs: List<ApiDebugEntry>,
    reportLabels: DebugReportLabels
) {
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
                    reportLabels = reportLabels,
                    onCopy = { copySingleLogToClipboard(context, log, reportLabels) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SessionInfoTab(sessionInfo: SessionDebugInfo?, onLogout: (() -> Unit)?) {
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
                    val text = DebugReportFormatter.formatText(debugLogs.joinToString("\n"))
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
                    text = DebugReportFormatter.formatText(log),
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
private fun ApiLogItem(
    log: ApiDebugEntry,
    reportLabels: DebugReportLabels,
    onCopy: () -> Unit
) {
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
                Text(
                    text = DebugReportFormatter.formatEntry(log, reportLabels),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
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

private fun copyDebugInfoToClipboard(
    context: Context,
    apiLogs: List<ApiDebugEntry>,
    sessionInfo: SessionDebugInfo?,
    reportLabels: DebugReportLabels
) {
    val content = DebugReportFormatter.formatText(buildString {
        appendLine("=== 钱包哨兵调试信息 ===")
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine()

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
        }

        appendLine("=== API 请求日志 (${apiLogs.size}) ===")
        appendLine(DebugReportFormatter.formatEntries(apiLogs, reportLabels))

        val debugLogs = DebugLogger.getLogs()
        if (debugLogs.isNotEmpty()) {
            appendLine("=== 调试日志 (${debugLogs.size}) ===")
            appendLine(DebugReportFormatter.formatText(debugLogs.joinToString("\n")))
        }
    })

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("调试信息", content)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制全部调试信息到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun copySingleLogToClipboard(
    context: Context,
    log: ApiDebugEntry,
    reportLabels: DebugReportLabels
) {
    val content = DebugReportFormatter.formatEntry(log, reportLabels)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API 日志", content)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun saveToFile(
    context: Context,
    apiLogs: List<ApiDebugEntry>,
    sessionInfo: SessionDebugInfo?,
    reportLabels: DebugReportLabels
) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "console_debug_$timestamp.json"
        val debugLogs = DebugLogger.getLogs()
        val content = DebugReportFormatter.formatText(
            org.json.JSONObject().apply {
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                sessionInfo?.let { info ->
                    put("session", org.json.JSONObject().apply {
                        put("platform_id", info.platformId)
                        put("platform_name", info.platformName)
                        put("is_logged_in", info.isLoggedIn)
                        put("is_session_valid", info.isSessionValid)
                        put("cookie_count", info.cookieCount)
                        put("local_storage_count", info.localStorageCount)
                        put("email", info.email.orEmpty())
                        put("current_url", info.currentUrl.orEmpty())
                        put("session_created_at", info.sessionCreatedAt.orEmpty())
                        put("session_expires_at", info.sessionExpiresAt.orEmpty())
                    })
                }
                put("api_logs", DebugReportFormatter.formatEntries(apiLogs, reportLabels))
                put("debug_logs", DebugReportFormatter.formatText(debugLogs.joinToString("\n")))
            }.toString(2)
        )

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
        val message = DebugReportFormatter.formatText(e.message.orEmpty())
        Toast.makeText(context, "保存失败: $message", Toast.LENGTH_SHORT).show()
    }
}
