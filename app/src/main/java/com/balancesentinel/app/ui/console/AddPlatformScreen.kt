package com.balancesentinel.app.ui.console

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.data.console.ConsoleOriginPolicy

/**
 * 添加平台页面 - 选择预设平台或添加自定义平台
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlatformScreen(
    addedPlatformIds: List<String>,
    onAddPreset: (ConsolePlatform) -> Unit,
    onAddCustom: (ConsolePlatform) -> Unit,
    onBack: () -> Unit
) {
    var showCustomForm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    if (showCustomForm) {
        CustomPlatformForm(
            onAdd = { platform ->
                onAddCustom(platform)
            },
            onBack = { showCustomForm = false }
        )
    } else {
        PresetPlatformList(
            addedPlatformIds = addedPlatformIds,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onAddPreset = onAddPreset,
            onAddCustom = { showCustomForm = true },
            onBack = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPlatformList(
    addedPlatformIds: List<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onAddPreset: (ConsolePlatform) -> Unit,
    onAddCustom: () -> Unit,
    onBack: () -> Unit
) {
    // 过滤预设平台
    val filteredPlatforms = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            PresetPlatforms.ALL
        } else {
            PresetPlatforms.ALL.filter { platform ->
                platform.name.contains(searchQuery, ignoreCase = true) ||
                platform.description?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "添加平台",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 搜索框
            SearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 预设平台部分
            Text(
                text = "预设平台",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "已适配的 AI 平台，可直接使用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 预设平台列表
            if (filteredPlatforms.isEmpty()) {
                EmptySearchResult(searchQuery = searchQuery)
            } else {
                filteredPlatforms.forEach { platform ->
                    val isAdded = addedPlatformIds.contains(platform.id)
                    PresetPlatformCard(
                        platform = platform,
                        isAdded = isAdded,
                        onClick = {
                            if (!isAdded) {
                                onAddPreset(platform)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 自定义平台部分
            CustomPlatformSection(onAddCustom = onAddCustom)
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("搜索平台...") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun EmptySearchResult(searchQuery: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "未找到匹配的平台",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "尝试其他关键词，或添加自定义平台",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CustomPlatformSection(onAddCustom: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "自定义平台",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "添加任意 OpenAI 兼容的 AI 平台",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            onClick = onAddCustom,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, "添加")
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加自定义平台")
        }
    }
}

@Composable
private fun PresetPlatformCard(
    platform: ConsolePlatform,
    isAdded: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAdded) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        enabled = !isAdded
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = platform.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isAdded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = platform.description ?: "预设平台",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isAdded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "已添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (!isAdded) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPlatformForm(
    onAdd: (ConsolePlatform) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf("") }
    var dashboardUrl by remember { mutableStateOf("") }
    var successUrlPatterns by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "添加自定义平台",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 平台名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("平台名称 *") },
                placeholder = { Text("例如：模力方舟") },
                isError = showErrors && name.isBlank(),
                supportingText = if (showErrors && name.isBlank()) {
                    { Text("请输入平台名称") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // 登录 URL
            OutlinedTextField(
                value = loginUrl,
                onValueChange = { loginUrl = it },
                label = { Text("登录页面 URL *") },
                placeholder = { Text("https://ai.gitee.com/login") },
                isError = showErrors && !isValidUrl(loginUrl),
                supportingText = if (showErrors && !isValidUrl(loginUrl)) {
                    { Text("请输入有效的 URL（以 http:// 或 https:// 开头）") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // 仪表盘 URL
            OutlinedTextField(
                value = dashboardUrl,
                onValueChange = { dashboardUrl = it },
                label = { Text("仪表盘 URL *") },
                placeholder = { Text("https://ai.gitee.com/dashboard") },
                isError = showErrors && !isValidUrl(dashboardUrl),
                supportingText = if (showErrors && !isValidUrl(dashboardUrl)) {
                    { Text("请输入有效的 URL") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // 成功 URL 模式
            OutlinedTextField(
                value = successUrlPatterns,
                onValueChange = { successUrlPatterns = it },
                label = { Text("登录成功 URL 模式 *") },
                placeholder = { Text("ai.gitee.com/dashboard,ai.gitee.com/console") },
                supportingText = {
                    Text("多个模式用逗号分隔，用于判断登录是否成功")
                },
                isError = showErrors && successUrlPatterns.isBlank(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // 描述（可选）
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("描述（可选）") },
                placeholder = { Text("查看账户余额、用量统计") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 添加按钮
            Button(
                onClick = {
                    showErrors = true
                    if (isValidInput(name, loginUrl, dashboardUrl, successUrlPatterns)) {
                        val platform = ConsolePlatform(
                            id = "custom_${System.currentTimeMillis()}",
                            name = name.trim(),
                            loginUrl = loginUrl.trim(),
                            dashboardUrl = dashboardUrl.trim(),
                            successUrlPatterns = successUrlPatterns.split(",").map { it.trim() },
                            description = description.trim().ifBlank { null }
                        )
                        onAdd(platform)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, "添加")
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加平台")
            }
        }
    }
}

private fun isValidUrl(url: String): Boolean {
    return ConsoleOriginPolicy.isValidHttpsUrl(url.trim())
}

private fun isValidInput(
    name: String,
    loginUrl: String,
    dashboardUrl: String,
    successUrlPatterns: String
): Boolean {
    return name.isNotBlank() &&
           isValidUrl(loginUrl) &&
           isValidUrl(dashboardUrl) &&
           successUrlPatterns.isNotBlank()
}
