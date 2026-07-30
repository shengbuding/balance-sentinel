package com.balancesentinel.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.ui.CustomIcons
import java.text.SimpleDateFormat
import java.util.*

/**
 * 账户调试对话框
 * 展示该账户的完整API请求和响应信息
 */
@Composable
fun DebugDialog(
    accountId: String,
    accountLabel: String,
    onDismiss: () -> Unit,
    accountUsageScript: String? = null
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(ApiDebugStore.getEntries(accountId)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "调试信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = accountLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 显示账户配置信息
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "账户配置",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "自定义脚本: ${if (accountUsageScript.isNullOrBlank()) "未配置 (null/empty)" else "已配置"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (accountUsageScript.isNullOrBlank()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Text(
                            text = "脚本长度: ${accountUsageScript?.length ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!accountUsageScript.isNullOrBlank()) {
                            Text(
                                text = "脚本前100字符:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = accountUsageScript.take(100),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (entries.isEmpty()) {
                    Text(
                        text = "暂无API调用记录\n\n刷新余额后将显示请求和响应详情",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    entries.forEach { entry ->
                        DebugEntryCard(entry = entry, context = context)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                // 全部复制按钮
                TextButton(
                    onClick = {
                        val text = buildString {
                            appendLine("=== 调试信息 - $accountLabel ===")
                            appendLine()
                            // 账户配置信息
                            appendLine("【账户配置】")
                            appendLine("自定义脚本: ${if (accountUsageScript.isNullOrBlank()) "未配置" else "已配置"}")
                            if (!accountUsageScript.isNullOrBlank()) {
                                appendLine("脚本内容:")
                                appendLine(accountUsageScript)
                            }
                            appendLine()
                            // 调试记录
                            appendLine("【调试记录】")
                            appendLine("记录数: ${entries.size}")
                            appendLine()
                            entries.forEachIndexed { index, entry ->
                                appendLine("--- 记录 ${index + 1} ---")
                                appendEntryDetails(this, entry)
                                appendLine()
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("API Debug All", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "已复制全部${entries.size}条记录", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        CustomIcons.ContentCopy,
                        contentDescription = "全部复制",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("全部复制")
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 清空按钮
                TextButton(
                    onClick = {
                        ApiDebugStore.clearEntries(accountId)
                        entries = emptyList()
                        Toast.makeText(context, "已清空调试记录", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "清空",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空")
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 关闭按钮
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

/**
 * 构建条目详情文本
 */
private fun appendEntryDetails(builder: StringBuilder, entry: ApiDebugEntry) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 基本信息
    builder.appendLine("请求: ${entry.method} ${entry.url}")
    builder.appendLine("状态码: ${entry.statusCode}")
    builder.appendLine("耗时: ${entry.duration}ms")
    builder.appendLine("时间: ${dateFormat.format(Date(entry.timestamp))}")

    // 标识信息
    if (!entry.accountLabel.isNullOrBlank()) {
        builder.appendLine("账户: ${entry.accountLabel}")
    }
    if (!entry.providerType.isNullOrBlank()) {
        builder.appendLine("供应商: ${entry.providerType}")
    }
    if (!entry.baseUrl.isNullOrBlank()) {
        builder.appendLine("Base URL: ${entry.baseUrl}")
    }
    if (!entry.endpoint.isNullOrBlank()) {
        builder.appendLine("端点: ${entry.endpoint}")
    }
    if (entry.isCustomScript) {
        builder.appendLine("使用自定义脚本: 是")
        if (!entry.scriptPreview.isNullOrBlank()) {
            builder.appendLine("脚本预览: ${entry.scriptPreview}...")
        }
    }

    // 请求头
    if (entry.requestHeaders.isNotEmpty()) {
        builder.appendLine()
        builder.appendLine("请求头:")
        entry.requestHeaders.forEach { (key, value) ->
            builder.appendLine("  $key: $value")
        }
    }

    // 请求体
    if (!entry.requestBody.isNullOrBlank()) {
        builder.appendLine()
        builder.appendLine("请求体:")
        builder.appendLine(entry.requestBody)
    }

    // 响应头
    if (entry.responseHeaders.isNotEmpty()) {
        builder.appendLine()
        builder.appendLine("响应头:")
        entry.responseHeaders.forEach { (key, value) ->
            builder.appendLine("  $key: $value")
        }
    }

    // 响应体
    if (entry.responseBody.isNotBlank()) {
        builder.appendLine()
        builder.appendLine("响应体:")
        builder.appendLine(entry.responseBody)
    }

    // 错误信息
    if (entry.error != null) {
        builder.appendLine()
        builder.appendLine("错误: ${entry.error}")
    }

    // 异常信息
    if (!entry.exceptionType.isNullOrBlank()) {
        builder.appendLine()
        builder.appendLine("异常类型: ${entry.exceptionType}")
    }
    if (!entry.exceptionStack.isNullOrBlank()) {
        builder.appendLine("异常堆栈:")
        builder.appendLine(entry.exceptionStack)
    }
}

/**
 * 单个调试条目卡片
 */
@Composable
private fun DebugEntryCard(entry: ApiDebugEntry, context: Context) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 请求信息行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 方法和状态码
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.method,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${entry.statusCode}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (entry.statusCode in 200..299) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                // 时间和耗时
                Text(
                    text = "${dateFormat.format(Date(entry.timestamp))} · ${entry.duration}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // URL
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 标识信息
            if (!entry.accountLabel.isNullOrBlank() || !entry.providerType.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!entry.providerType.isNullOrBlank()) {
                        Text(
                            text = "供应商: ${entry.providerType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!entry.accountLabel.isNullOrBlank()) {
                        Text(
                            text = "账户: ${entry.accountLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 端点信息
            if (!entry.endpoint.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "端点: ${entry.endpoint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 自定义脚本标识
            if (entry.isCustomScript) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "使用自定义脚本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 请求头
            if (entry.requestHeaders.isNotEmpty()) {
                Text(
                    text = "请求头:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.requestHeaders.forEach { (key, value) ->
                    Text(
                        text = "  $key: $value",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 请求体
            if (!entry.requestBody.isNullOrBlank()) {
                Text(
                    text = "请求体:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.requestBody.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 响应体
            if (entry.responseBody.isNotBlank()) {
                Text(
                    text = "响应体:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.responseBody.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 错误信息
            if (entry.error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "错误: ${entry.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 异常信息
            if (!entry.exceptionType.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "异常: ${entry.exceptionType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 复制按钮
            Button(
                onClick = {
                    val text = buildString {
                        appendEntryDetails(this, entry)
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("API Debug", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    CustomIcons.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("复制详情")
            }
        }
    }
}
