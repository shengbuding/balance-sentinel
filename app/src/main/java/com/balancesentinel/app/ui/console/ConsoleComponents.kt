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
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════
// 调试面板
// ═══════════════════════════════════════════════════════════

@Composable
fun ApiDebugPanel(
    apiLogs: List<ApiLogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
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
                    apiLogs.forEach { log ->
                        ApiLogItem(log = log)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiLogItem(log: ApiLogEntry) {
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
                horizontalArrangement = Arrangement.SpaceBetween
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
                    color = if (log.statusCode in 200..299) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // URL
            Text(
                text = log.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )

            // 响应体（仅 Debug 构建）
            if (BuildConfig.DEBUG && log.responseBody.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = log.responseBody.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

private fun copyAllToClipboard(context: Context, apiLogs: List<ApiLogEntry>) {
    val text = apiLogs.joinToString("\n") { "${it.method} ${it.url} -> ${it.statusCode}" }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API Logs", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun saveToFile(context: Context, apiLogs: List<ApiLogEntry>) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "console_logs_$timestamp.json"

        // 获取调试日志
        val debugLogs = DebugLogger.getLogs()

        val content = buildString {
            appendLine("{")
            // API 请求日志
            appendLine("  \"api_logs\": [")
            apiLogs.forEachIndexed { index, log ->
                appendLine("    {")
                appendLine("      \"url\": \"${log.url}\",")
                appendLine("      \"method\": \"${log.method}\",")
                appendLine("      \"statusCode\": ${log.statusCode},")
                append("      \"response\": ")
                append(org.json.JSONObject.quote(log.responseBody))
                appendLine()
                append(if (index < apiLogs.size - 1) "," else "")
                appendLine()
                appendLine("    }")
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
