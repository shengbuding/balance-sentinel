package com.balancesentinel.app.ui.console

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.balancesentinel.app.BuildConfig
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.ConsoleUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台页面 - 统一处理登录和数据显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    platform: ConsolePlatform,
    uiState: ConsoleUiState,
    onLoginSuccess: (cookies: Map<String, String>, localStorage: Map<String, String>, email: String?) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoggedIn = uiState.isLoggedIn

    DebugLogger.log("[ConsoleScreen] Render: platform=${platform.name}, id=${platform.id}, isLoggedIn=$isLoggedIn, sessionCookies=${uiState.session?.cookies?.size ?: 0}")

    if (isLoggedIn) {
        ConsoleDashboard(
            platform = platform,
            session = uiState.session,
            onLogout = onLogout,
            onBack = onBack,
            modifier = modifier
        )
    } else {
        ConsoleLogin(
            platform = platform,
            onLoginSuccess = onLoginSuccess,
            onBack = onBack,
            modifier = modifier
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 登录页面
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleLogin(
    platform: ConsolePlatform,
    onLoginSuccess: (cookies: Map<String, String>, localStorage: Map<String, String>, email: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loginDetected by remember { mutableStateOf(false) }
    var cookiesSaved by remember { mutableStateOf(false) }
    var skipLogin by remember { mutableStateOf(false) }

    // 登录成功后立即保存 cookies 和 localStorage
    LaunchedEffect(loginDetected) {
        if (loginDetected && !cookiesSaved) {
            DebugLogger.log("[ConsoleLogin] Login detected, extracting cookies and localStorage")
            webView?.let { wv ->
                extractCookies(wv, platform) { cookies, localStorage, token, email ->
                    val allCookies = cookies.toMutableMap()
                    if (!token.isNullOrBlank()) {
                        allCookies["token"] = token
                    }
                    DebugLogger.log("[ConsoleLogin] Cookies saved: ${allCookies.size}, localStorage: ${localStorage.size}")
                    cookiesSaved = true
                    onLoginSuccess(allCookies, localStorage, email)
                }
            }
        }
    }

    // 跳过登录时，先尝试提取当前 cookies，再进入控制台
    if (skipLogin) {
        // 如果还没有保存过 cookies，先尝试提取
        if (!cookiesSaved) {
            webView?.let { wv ->
                extractCookies(wv, platform) { cookies, localStorage, token, email ->
                    val allCookies = cookies.toMutableMap()
                    if (!token.isNullOrBlank()) {
                        allCookies["token"] = token
                    }
                    if (allCookies.isNotEmpty()) {
                        DebugLogger.log("[ConsoleLogin] Skip login but saving cookies: ${allCookies.size}")
                        cookiesSaved = true
                        onLoginSuccess(allCookies, localStorage, email)
                    }
                }
            }
        }

        ConsoleDashboard(
            platform = platform,
            session = null,
            onLogout = { skipLogin = false },
            onBack = onBack,
            modifier = modifier
        )
        return
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "登录 ${platform.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 跳过登录按钮
                    TextButton(onClick = { skipLogin = true }) {
                        Text("跳过登录")
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        configureWebView()

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                hasError = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                DebugLogger.log("[ConsoleLogin] Page finished: $url")

                                if (url != null && !loginDetected) {
                                    val isSuccess = platform.isLoginSuccess(url)
                                    DebugLogger.log("[ConsoleLogin] isLoginSuccess($url) = $isSuccess")

                                    if (isSuccess) {
                                        loginDetected = true
                                        DebugLogger.log("[${platform.name}] Login success detected: $url")
                                    }
                                }
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                    errorMessage = error?.description?.toString() ?: "加载失败"
                                    isLoading = false
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val scheme = request?.url?.scheme ?: return false
                                return scheme !in listOf("http", "https")
                            }
                        }

                        webChromeClient = WebChromeClient()
                        webView = this
                        loadUrl(platform.loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            DisposableEffect(Unit) {
                onDispose {
                    webView?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                }
            }

            // 加载指示器
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }

            // 登录成功提示
            if (loginDetected && !cookiesSaved) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "登录成功，正在保存...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 错误提示
            if (hasError) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "加载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = {
                            hasError = false
                            loginDetected = false
                            webView?.reload()
                        }) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 控制台仪表盘
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleDashboard(
    platform: ConsolePlatform,
    session: com.balancesentinel.app.data.console.store.ConsoleSession?,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var loginExpired by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearSessionDialog by remember { mutableStateOf(false) }
    val apiLogs = remember { mutableStateListOf<ApiLogEntry>() }
    val context = LocalContext.current

    val cookies = session?.cookies ?: emptyMap()
    val localStorage = session?.localStorage ?: emptyMap()

    // 获取自定义平台的域名（用于 API 拦截）
    val platformDomains = remember {
        try {
            val dashboardHost = java.net.URL(platform.dashboardUrl).host
            val loginHost = java.net.URL(platform.loginUrl).host
            listOf(dashboardHost, loginHost).filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 构建会话调试信息
    val sessionDebugInfo = remember(session, currentUrl) {
        SessionDebugInfo(
            platformId = platform.id,
            platformName = platform.name,
            isLoggedIn = session != null,
            isSessionValid = session != null,
            cookieCount = session?.cookies?.size ?: 0,
            localStorageCount = session?.localStorage?.size ?: 0,
            email = session?.email,
            currentUrl = currentUrl,
            cookies = session?.cookies ?: emptyMap(),
            localStorage = session?.localStorage ?: emptyMap(),
            sessionCreatedAt = session?.loginTime?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it)) },
            sessionExpiresAt = null // 当前实现没有过期时间
        )
    }

    // 检测登录失效
    LaunchedEffect(currentUrl) {
        currentUrl?.let { url ->
            if (isLoginPage(url, platform) && !loginExpired) {
                loginExpired = true
                DebugLogger.log("[ConsoleDashboard] Login expired detected, current URL: $url")
            }
        }
    }

    // 登录失效时自动返回登录页面
    if (loginExpired) {
        AlertDialog(
            onDismissRequest = { loginExpired = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("登录已失效") },
            text = { Text("检测到您的登录状态已失效，请重新登录") },
            confirmButton = {
                Button(onClick = {
                    loginExpired = false
                    onLogout()
                }) {
                    Text("重新登录")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { loginExpired = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 清除登录状态确认对话框
    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("清除登录状态") },
            text = {
                Column {
                    Text("确定要清除「${platform.name}」的登录状态吗？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• 所有登录信息将被清除\n• 需要重新登录才能访问控制台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearSessionDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearSessionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 返回时不清除数据，只返回
    val handleBack = {
        onBack()
    }

    // 处理系统返回键
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            handleBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${platform.name} 控制台",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (session?.email != null) {
                            Text(
                                text = session.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            CustomIcons.Analytics,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text("调试面板")
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showDebugPanel = !showDebugPanel
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text("刷新页面")
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    webView?.reload()
                                    apiLogs.clear()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            "清除登录状态",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showClearSessionDialog = true
                                }
                            )
                        }
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
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ConsoleWebView(
                    url = platform.dashboardUrl,
                    cookies = cookies,
                    localStorage = localStorage,
                    instanceId = platform.id,
                    onLoadingChange = { isLoading = it },
                    onApiRequest = { request ->
                        interceptApiRequest(request, apiLogs, platform.id, platformDomains)
                    },
                    onPageFinished = { url ->
                        currentUrl = url
                        DebugLogger.log("[ConsoleDashboard] Page finished: $url")
                    },
                    onReceivedError = { error ->
                        DebugLogger.log("[ConsoleDashboard] Error: $error")
                    },
                    webView = { webView = it }
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showDebugPanel) {
                    ApiDebugPanel(
                        apiLogs = apiLogs.toList(),
                        sessionInfo = sessionDebugInfo,
                        onDismiss = { showDebugPanel = false },
                        onClear = {
                            apiLogs.clear()
                            DebugLogger.clear()
                        },
                        onLogout = { showClearSessionDialog = true }
                    )
                }
            }
        }
    }
}

/**
 * 检测是否是登录页面
 */
private fun isLoginPage(url: String, platform: ConsolePlatform): Boolean {
    // 检查 URL 是否匹配登录页面模式
    if (platform.loginPagePatterns.any { url.contains(it, ignoreCase = true) }) {
        return true
    }

    // 检查是否不是成功页面
    if (platform.successUrlPatterns.any { url.contains(it, ignoreCase = true) }) {
        return false
    }

    // 检查通用登录页面模式
    val loginPatterns = listOf("/sign_in", "/login", "/register", "/oauth", "/signin", "/auth")
    return loginPatterns.any { url.contains(it, ignoreCase = true) }
}

/**
 * 检查 session 是否过期
 * 当前实现中 session 永不过期，但可以通过其他方式检测失效
 */
private fun isSessionExpired(session: com.balancesentinel.app.data.console.store.ConsoleSession?): Boolean {
    if (session == null) return true
    // 当前实现中 session 永不过期
    return false
}

// ═══════════════════════════════════════════════════════════
// WebView 组件
// ═══════════════════════════════════════════════════════════

@Composable
private fun ConsoleWebView(
    url: String,
    cookies: Map<String, String> = emptyMap(),
    localStorage: Map<String, String> = emptyMap(),
    instanceId: String = "",
    onLoadingChange: (Boolean) -> Unit,
    onApiRequest: ((WebResourceRequest?) -> WebResourceResponse?)? = null,
    onPageFinished: ((String?) -> Unit)? = null,
    onReceivedError: ((String?) -> Unit)? = null,
    webView: (WebView) -> Unit = {}
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let {
                it.stopLoading()
                it.destroy()
            }
        }
    }

    AndroidView(
        factory = { context ->
            DebugLogger.log("[ConsoleWebView] Creating WebView for instance: $instanceId, url: $url")

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

                // 启用 cookies
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                // 保存 cookies 和 localStorage 以便在页面加载后注入
                val cookiesToInject = cookies
                val localStorageToInject = localStorage

                // 获取当前域名
                val currentDomain = try {
                    java.net.URL(url).host
                } catch (e: Exception) {
                    ""
                }

                // 注入 session 中保存的 cookies（如果有的话）
                // 注意：不再清除现有 cookies，保留 WebView 中已有的登录状态
                if (cookiesToInject.isNotEmpty() && currentDomain.isNotBlank()) {
                    try {
                        val cookieString = cookiesToInject.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        cookieManager.setCookie(currentDomain, cookieString)
                        cookieManager.flush()
                        DebugLogger.log("[ConsoleWebView] Injected ${cookiesToInject.size} cookies for $currentDomain")
                    } catch (e: Exception) {
                        DebugLogger.log("[ConsoleWebView] Failed to inject cookies: ${e.message}")
                    }
                } else {
                    DebugLogger.log("[ConsoleWebView] No cookies to inject for $currentDomain")
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChange(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChange(false)
                        onPageFinished?.invoke(url)

                        // 页面加载完成后注入 localStorage
                        if (localStorageToInject.isNotEmpty()) {
                            val localStorageScript = buildString {
                                append("(function() {")
                                append("try {")
                                localStorageToInject.forEach { (key, value) ->
                                    val escapedKey = key.replace("\\", "\\\\").replace("'", "\\'")
                                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                                    val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'")
                                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                                    append("localStorage.setItem('$escapedKey', '$escapedValue');")
                                }
                                append("} catch(e) {}")
                                append("})()")
                            }
                            view?.evaluateJavascript(localStorageScript) { result ->
                                DebugLogger.log("[ConsoleWebView] Injected ${localStorageToInject.size} localStorage items")
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            val errorMsg = error?.description?.toString() ?: "Unknown error"
                            DebugLogger.log("[ConsoleWebView] Error: $errorMsg")
                            onReceivedError?.invoke(errorMsg)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return onApiRequest?.invoke(request) ?: super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = WebChromeClient()
                webView(this)
                webViewRef = this
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

private fun WebView.configureWebView() {
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

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureWebView, true)
    }
}

/**
 * 从 WebView 提取 Cookie 和 localStorage
 */
private fun extractCookies(
    webView: WebView?,
    platform: ConsolePlatform,
    callback: (cookies: Map<String, String>, localStorage: Map<String, String>, token: String?, email: String?) -> Unit
) {
    val url = webView?.url ?: run {
        callback(emptyMap(), emptyMap(), null, null)
        return
    }

    DebugLogger.log("[extractCookies] Extracting cookies from URL: $url")

    // 使用 CookieManager 提取 cookies
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()

    val cookieString = try {
        cookieManager.getCookie(url)
    } catch (e: Exception) {
        DebugLogger.log("[extractCookies] Failed to get cookie: ${e.message}")
        null
    }

    val cookies = if (!cookieString.isNullOrBlank()) {
        cookieString.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
    } else {
        emptyMap()
    }

    DebugLogger.log("[extractCookies] Extracted ${cookies.size} cookies")

    // 从 localStorage 提取所有数据
    webView.evaluateJavascript(
        """
        (function() {
            try {
                var result = {};
                for (var i = 0; i < localStorage.length; i++) {
                    var key = localStorage.key(i);
                    result[key] = localStorage.getItem(key);
                }
                return JSON.stringify(result);
            } catch(e) { return '{}'; }
        })()
        """.trimIndent()
    ) { localStorageResult ->
        val localStorage = try {
            val jsonStr = localStorageResult?.removeSurrounding("\"") ?: "{}"
            val jsonObject = org.json.JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                map[key] = jsonObject.getString(key)
            }
            map.toMap()
        } catch (e: Exception) {
            DebugLogger.log("[extractCookies] Failed to parse localStorage: ${e.message}")
            emptyMap()
        }

        // 提取 token
        val token = localStorage["userToken"] ?: localStorage["token"] ?: localStorage["access_token"]

        DebugLogger.log("[extractCookies] Extracted ${localStorage.size} localStorage items")
        callback(cookies, localStorage, token, null)
    }
}

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

// ═══════════════════════════════════════════════════════════
// API 拦截
// ═══════════════════════════════════════════════════════════

/** 允许拦截的域名白名单 */
private val ALLOWED_DOMAINS = listOf(
    "platform.deepseek.com",
    "api.deepseek.com",
    "platform.xiaomimimo.com",
    "api.xiaomimimo.com"
)

/**
 * 拦截 API 请求并获取完整响应
 * @return WebResourceResponse 或 null（交给默认处理）
 */
private fun interceptApiRequest(
    request: WebResourceRequest?,
    apiLogs: MutableList<ApiLogEntry>,
    tag: String,
    additionalDomains: List<String> = emptyList()
): WebResourceResponse? {
    val reqUrl = request?.url?.toString() ?: ""
    val method = request?.method ?: "GET"

    // 合并白名单域名
    val allAllowedDomains = ALLOWED_DOMAINS + additionalDomains

    // 检查是否是需要拦截的请求
    val isApiRequest = reqUrl.contains("/api/") || reqUrl.contains("/v1/") || reqUrl.contains("/v2/")
    val isAllowedDomain = allAllowedDomains.any { reqUrl.contains(it) }

    if (!isApiRequest || !isAllowedDomain) {
        return null
    }

    // POST/PUT/PATCH 请求体无法通过 shouldInterceptRequest 获取，直接放行
    if (request?.method != "GET") return null

    DebugLogger.log("[$tag] API: $method $reqUrl")

    // 记录请求头
    val requestHeaders = mutableMapOf<String, String>()
    request?.requestHeaders?.forEach { (key, value) ->
        requestHeaders[key] = value
    }

    return try {
        val connection = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        // 复制请求头
        requestHeaders.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        // 添加 Cookie
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(reqUrl)
        if (cookies != null) {
            connection.setRequestProperty("Cookie", cookies)
            requestHeaders["Cookie"] = cookies
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

        // 记录响应头
        val responseHeaders = connection.headerFields.entries
            .filter { it.key != null }
            .associate { it.key to it.value.joinToString(", ") }

        // 记录日志
        val entry = ApiLogEntry(
            url = reqUrl,
            method = method,
            statusCode = statusCode,
            responseBody = responseBody,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders
        )
        synchronized(apiLogs) {
            apiLogs.removeAll { it.url == reqUrl }
            apiLogs.add(entry)
            if (apiLogs.size > 100) apiLogs.removeFirst()
        }

        // 检测认证失败（401/403）
        if (statusCode == 401 || statusCode == 403) {
            DebugLogger.log("[$tag] Auth failed: $statusCode $reqUrl")
        }

        val contentType = connection.contentType ?: "application/json"
        val encoding = connection.contentEncoding ?: "utf-8"

        WebResourceResponse(
            contentType,
            encoding,
            statusCode,
            connection.responseMessage,
            responseHeaders,
            responseBody.byteInputStream()
        )
    } catch (e: Exception) {
        DebugLogger.log("[$tag] Error: ${e.message}")

        // 记录错误日志
        val entry = ApiLogEntry(
            url = reqUrl,
            method = method,
            statusCode = 0,
            responseBody = "",
            requestHeaders = requestHeaders,
            error = e.message
        )
        synchronized(apiLogs) {
            apiLogs.removeAll { it.url == reqUrl }
            apiLogs.add(entry)
            if (apiLogs.size > 100) apiLogs.removeFirst()
        }

        null
    }
}
