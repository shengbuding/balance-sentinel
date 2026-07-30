package com.balancesentinel.app.ui.console

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.CustomIcons

/**
 * 控制台选择页面 - 显示所有已添加的平台
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSelectScreen(
    onSelectPlatform: (ConsolePlatform) -> Unit,
    onAddPlatform: () -> Unit,
    refreshTrigger: Int = 0 // 用于触发刷新
) {
    val context = LocalContext.current
    val store = remember { ConsoleStore(context) }
    // 每次进入页面时都重新读取平台列表和登录状态
    var platforms by remember { mutableStateOf(store.getPlatforms()) }

    // 当refreshTrigger变化时刷新数据
    LaunchedEffect(refreshTrigger) {
        platforms = store.getPlatforms()
    }

    // 删除确认对话框
    var showDeleteDialog by remember { mutableStateOf(false) }
    var platformToDelete by remember { mutableStateOf<ConsolePlatform?>(null) }

    if (showDeleteDialog && platformToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                platformToDelete = null
            },
            title = { Text("删除平台") },
            text = { Text("确定要删除「${platformToDelete?.name}」吗？删除后将清除所有登录数据。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        platformToDelete?.let { platform ->
                            store.removePlatform(platform.id)
                            platforms = store.getPlatforms()
                        }
                        showDeleteDialog = false
                        platformToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        platformToDelete = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "控制台",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 说明文字
            Text(
                text = "选择要进入的控制台",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 平台列表
            if (platforms.isEmpty()) {
                // 空状态
                EmptyStateCard(onAddPlatform = onAddPlatform)
            } else {
                // 平台列表
                platforms.forEach { platform ->
                    PlatformCard(
                        platform = platform,
                        isLoggedIn = store.hasValidSession(platform.id),
                        onClick = { onSelectPlatform(platform) },
                        onDelete = {
                            platformToDelete = platform
                            showDeleteDialog = true
                        },
                        onFixConfig = if (!platform.isPreset) {
                            // 检查是否能匹配到预设配置
                            val matchedPreset = findMatchingPreset(platform)
                            if (matchedPreset != null) {
                                {
                                    // 修复配置：使用预设的 successUrlPatterns
                                    val fixedPlatform = platform.copy(
                                        successUrlPatterns = matchedPreset.successUrlPatterns,
                                        loginPagePatterns = matchedPreset.loginPagePatterns
                                    )
                                    store.updatePlatform(fixedPlatform)
                                    platforms = store.getPlatforms()
                                }
                            } else null
                        } else null
                    )
                }
            }

            // 添加平台按钮
            Spacer(modifier = Modifier.height(8.dp))
            AddPlatformButton(onClick = onAddPlatform)
        }
    }
}

@Composable
private fun EmptyStateCard(onAddPlatform: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Text(
                text = "暂无控制台",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "添加控制台后可以查看账户余额、用量统计等信息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAddPlatform,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, "添加")
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加控制台")
            }
        }
    }
}

@Composable
private fun PlatformCard(
    platform: ConsolePlatform,
    isLoggedIn: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFixConfig: (() -> Unit)? = null
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // 主内容区域（可点击进入）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 平台图标
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .padding(end = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getPlatformIcon(platform.id),
                        contentDescription = platform.name,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // 平台信息（可点击）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .noRippleClickable { onClick() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = platform.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isLoggedIn) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "已登录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = platform.description ?: "控制台",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 操作按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 更多按钮
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "更多",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 进入按钮
                    IconButton(
                        onClick = { onClick() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            "进入",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 展开的选项列表
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // 修复配置按钮（仅对自定义平台且能匹配到预设配置时显示）
                    if (onFixConfig != null) {
                        TextButton(
                            onClick = {
                                isExpanded = false
                                onFixConfig()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Build,
                                "修复",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "修复配置",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // 删除按钮
                    TextButton(
                        onClick = {
                            isExpanded = false
                            onDelete()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "删除",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "删除平台",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

private fun getPlatformIcon(platformId: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (platformId) {
        "deepseek" -> Icons.Default.Star
        "mimo" -> Icons.Default.Face
        "model_ark" -> Icons.Default.AccountCircle
        else -> Icons.Default.Info
    }
}

/**
 * 查找匹配的预设平台配置
 * 根据自定义平台的 loginUrl 或 dashboardUrl 匹配预设平台
 */
private fun findMatchingPreset(platform: ConsolePlatform): ConsolePlatform? {
    val url = (platform.loginUrl + platform.dashboardUrl).lowercase()
    return PresetPlatforms.ALL.find { preset ->
        val presetUrl = (preset.loginUrl + preset.dashboardUrl).lowercase()
        // 检查域名是否匹配
        val presetDomain = try {
            java.net.URL(preset.loginUrl).host
        } catch (e: Exception) {
            ""
        }
        val platformDomain = try {
            java.net.URL(platform.loginUrl).host
        } catch (e: Exception) {
            ""
        }
        presetDomain.isNotBlank() && presetDomain == platformDomain
    }
}

@Composable
private fun AddPlatformButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Add, "添加")
        Spacer(modifier = Modifier.width(8.dp))
        Text("添加平台")
    }
}

// 无水波纹点击效果
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    return this.then(
        Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onClick() }
    )
}
